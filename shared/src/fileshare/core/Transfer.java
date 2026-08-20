package fileshare.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One round of the conversation, from each side.
 *
 * The session is turn-based: the laptop offers, the phone answers and receives,
 * then the phone offers and the laptop receives. Either queue may be empty, and
 * an empty round is also the heartbeat, so a dead path surfaces in about a
 * second rather than on a TCP timeout.
 *
 * Pausing and cancelling happen *in band*. File data is carried in framed
 * chunks, so between any two chunks either end can send PAUSE or CANCEL and get
 * an acknowledgement back without the connection going anywhere. An earlier
 * design stopped a transfer by dropping the socket, which meant every pause also
 * re-offered the whole queue, re-prompted for files already answered, and left
 * the two sides disagreeing about what was paused.
 */
public final class Transfer {
    private Transfer() {}

    /** Raised when the peer drops the pairing while we are connected. */
    public static final class Unpaired extends IOException {
        public Unpaired() { super("the other device removed this pairing"); }
    }

    private static final long PROGRESS_EVERY_MS = 200;

    private static long rate(long bytes, long millis) {
        return millis <= 0 ? 0 : bytes * 1000L / millis;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** @return outcome per item id, so the caller can update its queue. */
    public static Map<String, Io.Outcome> sendRound(Session s, List<Item> queue, Io.Source src,
                                                    Io.Events ev, Io.Control control)
            throws IOException {

        Map<String, Io.Outcome> result = new LinkedHashMap<String, Io.Outcome>();

        Wire.Buf b = Wire.buf();
        Item.encodeList(b, queue);
        s.send(Wire.OFFER, b.done());

        Wire.Frame f = s.take();
        if (f.type == Wire.ERROR) throw new IOException(Wire.errorText(f));
        if (f.type == Wire.UNPAIR) throw new Unpaired();
        if (f.type != Wire.OFFER_REPLY) {
            throw new IOException("expected offer reply, got 0x" + Integer.toHexString(f.type & 0xff));
        }

        DataInputStream d = f.in();
        int n = d.readInt();
        Map<String, long[]> reply = new HashMap<String, long[]>();
        for (int i = 0; i < n; i++) {
            String id = d.readUTF();
            boolean accept = d.readBoolean();
            long have = d.readLong();
            reply.put(id, new long[]{accept ? 1 : 0, have});
        }

        for (Item it : queue) {
            long[] r = reply.get(it.id);
            if (r == null || r[0] == 0) {
                result.put(it.id, Io.Outcome.REJECTED);
                continue;
            }
            long have = r[1];
            if (have < 0 || have > it.size) have = 0;
            it.have = have;
            result.put(it.id, sendOne(s, it, src, ev, control));
        }
        return result;
    }

    private static Io.Outcome sendOne(Session s, Item it, Io.Source src,
                                      Io.Events ev, Io.Control control) throws IOException {
        long count = it.size - it.have;
        s.send(Wire.FILE_BEGIN, Wire.buf().str(it.id).i64(it.have).i64(count).done());
        ev.onStart(it, it.have, true);

        MessageDigest md = Hexes.sha256();
        byte[] buf = new byte[Io.BUFFER];
        long read = 0, sent = 0;
        long started = System.currentTimeMillis();
        long lastReport = started, windowStart = started, windowBytes = 0;

        InputStream fin = src.open(it);
        try {
            while (read < it.size) {
                Io.Act act = decide(s, it, control);
                if (act == Io.Act.PAUSE) {
                    s.send(Wire.FILE_PAUSED, Wire.buf().str(it.id).i64(it.have + sent).done());
                    ev.onPaused(it, it.have + sent, it.size, true);
                    return Io.Outcome.PAUSED;
                }
                if (act == Io.Act.CANCEL) {
                    s.send(Wire.FILE_CANCELLED, Wire.buf().str(it.id).done());
                    ev.onCancelled(it);
                    return Io.Outcome.CANCELLED;
                }

                int want = (int) Math.min(buf.length, it.size - read);
                int got = fin.read(buf, 0, want);
                if (got < 0) {
                    throw new IOException("file ended early: " + it.name
                            + " (moved or edited since it was queued)");
                }
                md.update(buf, 0, got);

                // Everything below the resume point is hashed but not transmitted.
                if (read + got > it.have) {
                    int from = (int) Math.max(0, it.have - read);
                    byte[] chunk = new byte[got - from];
                    System.arraycopy(buf, from, chunk, 0, chunk.length);
                    s.send(Wire.FILE_CHUNK, chunk);
                    sent += chunk.length;
                    windowBytes += chunk.length;
                }
                read += got;

                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_EVERY_MS) {
                    lastReport = now;
                    ev.onBytes(it, it.have + sent, it.size, rate(windowBytes, now - windowStart));
                    windowStart = now;
                    windowBytes = 0;
                }
            }
        } finally {
            try { fin.close(); } catch (IOException ignored) { }
        }

        if (sent != count) throw new IOException("size changed under us: " + it.name);

        s.send(Wire.FILE_END, Wire.buf().str(it.id).str(Hexes.hex(md.digest())).done());

        Wire.Frame res = s.take();
        if (res.type != Wire.FILE_RESULT) throw new IOException("expected file result");
        DataInputStream rd = res.in();
        rd.readUTF();
        boolean ok = rd.readBoolean();
        String msg = rd.readUTF();

        ev.onBytes(it, it.size, it.size, rate(sent, System.currentTimeMillis() - started));
        ev.onDone(it, ok, msg);
        return ok ? Io.Outcome.DONE : Io.Outcome.FAILED;
    }

    /** Local intent and the peer's request, whichever asks for more. */
    private static Io.Act decide(Session s, Item it, Io.Control control) {
        Io.Act local = control.check(it);
        if (local != Io.Act.GO) return local;

        Wire.Frame c = s.pollControl();
        while (c != null) {
            try {
                String id = c.in().readUTF();
                if (id.equals(it.id)) {
                    if (c.type == Wire.PAUSE) return Io.Act.PAUSE;
                    if (c.type == Wire.CANCEL) return Io.Act.CANCEL;
                }
            } catch (IOException ignored) {
                // Malformed control frame; nothing sensible to do but skip it.
            }
            c = s.pollControl();
        }
        return Io.Act.GO;
    }

    // ------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------

    public static Map<String, Io.Outcome> receiveRound(Session s, Io.Sink sink, Io.Approver approver,
                                                       Io.Events ev, Io.Control control)
            throws IOException {

        Map<String, Io.Outcome> result = new LinkedHashMap<String, Io.Outcome>();

        Wire.Frame f = s.take();
        if (f.type == Wire.ERROR) throw new IOException(Wire.errorText(f));
        if (f.type == Wire.UNPAIR) throw new Unpaired();
        if (f.type == Wire.BYE) throw new IOException("peer closed the session");
        if (f.type != Wire.OFFER) {
            throw new IOException("expected an offer, got 0x" + Integer.toHexString(f.type & 0xff));
        }

        List<Item> offered = Item.decodeList(f.in());
        for (Item it : offered) {
            it.have = sink.resumeOffset(it);
            if (it.have < 0 || it.have > it.size) it.have = 0;
        }

        boolean[] accept;
        if (offered.isEmpty()) {
            accept = new boolean[0];
        } else {
            accept = approver.decide(offered);
            if (accept == null || accept.length != offered.size()) {
                throw new IOException("approver returned a malformed decision");
            }
            long need = 0;
            for (int i = 0; i < offered.size(); i++) {
                if (accept[i]) need += offered.get(i).size - offered.get(i).have;
            }
            long free = sink.freeSpace();
            if (free > 0 && need > free - (64L * 1024 * 1024)) {
                ev.log("Not enough free space: need " + Hexes.humanBytes(need)
                        + ", have " + Hexes.humanBytes(free));
                for (int i = 0; i < accept.length; i++) accept[i] = false;
            }
        }

        Wire.Buf b = Wire.buf().i32(offered.size());
        for (int i = 0; i < offered.size(); i++) {
            Item it = offered.get(i);
            it.accepted = accept[i];
            b.str(it.id).bool(accept[i]).i64(accept[i] ? it.have : 0L);
        }
        s.send(Wire.OFFER_REPLY, b.done());

        for (Item it : offered) {
            if (!it.accepted) {
                result.put(it.id, Io.Outcome.REJECTED);
                continue;
            }
            result.put(it.id, receiveOne(s, it, sink, ev, control));
        }
        return result;
    }

    private static Io.Outcome receiveOne(Session s, Item it, Io.Sink sink,
                                         Io.Events ev, Io.Control control) throws IOException {
        Wire.Frame f = s.take();
        if (f.type != Wire.FILE_BEGIN) throw new IOException("expected file begin");
        DataInputStream d = f.in();
        String id = d.readUTF();
        long offset = d.readLong();
        long count = d.readLong();

        if (!id.equals(it.id)) throw new IOException("sender sent files out of order");
        if (offset < 0 || count < 0 || offset + count != it.size) {
            throw new IOException("inconsistent length for " + it.name);
        }

        ev.onStart(it, offset, false);

        Io.SinkFile out = sink.open(it, offset);
        boolean keepPartial = true;
        try {
            long got = 0, sinceSync = 0;
            long started = System.currentTimeMillis();
            long lastReport = started, windowStart = started, windowBytes = 0;
            boolean asked = false;

            while (true) {
                // Ask the sender to stop; it answers with FILE_PAUSED or
                // FILE_CANCELLED, and until then we keep taking what arrives.
                if (!asked) {
                    Io.Act act = control.check(it);
                    if (act == Io.Act.PAUSE) {
                        s.send(Wire.PAUSE, Wire.buf().str(it.id).done());
                        asked = true;
                    } else if (act == Io.Act.CANCEL) {
                        s.send(Wire.CANCEL, Wire.buf().str(it.id).done());
                        asked = true;
                    }
                }

                Wire.Frame fr = s.take();

                if (fr.type == Wire.FILE_CHUNK) {
                    out.write(fr.payload, 0, fr.payload.length);
                    got += fr.payload.length;
                    sinceSync += fr.payload.length;
                    windowBytes += fr.payload.length;

                    if (sinceSync >= Io.SYNC_EVERY) {
                        out.syncPoint();
                        sinceSync = 0;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= PROGRESS_EVERY_MS) {
                        lastReport = now;
                        ev.onBytes(it, offset + got, it.size, rate(windowBytes, now - windowStart));
                        windowStart = now;
                        windowBytes = 0;
                    }
                    continue;
                }

                if (fr.type == Wire.FILE_PAUSED) {
                    out.syncPoint();
                    ev.onPaused(it, offset + got, it.size, false);
                    return Io.Outcome.PAUSED;
                }

                if (fr.type == Wire.FILE_CANCELLED) {
                    out.discard();
                    keepPartial = false;
                    ev.onCancelled(it);
                    return Io.Outcome.CANCELLED;
                }

                if (fr.type == Wire.FILE_END) {
                    out.syncPoint();
                    DataInputStream ed = fr.in();
                    ed.readUTF();
                    String senderHash = ed.readUTF();

                    String ours = out.digestHex();
                    if (!ours.equalsIgnoreCase(senderHash)) {
                        out.discard();
                        keepPartial = false;
                        s.send(Wire.FILE_RESULT, Wire.buf()
                                .str(it.id).bool(false).str("Checksum mismatch, discarded").done());
                        ev.onDone(it, false, "Checksum mismatch, discarded");
                        return Io.Outcome.FAILED;
                    }

                    out.commitVisible();
                    keepPartial = false;
                    s.send(Wire.FILE_RESULT, Wire.buf().str(it.id).bool(true).str("").done());
                    ev.onBytes(it, it.size, it.size, rate(got, System.currentTimeMillis() - started));
                    ev.onDone(it, true, "");
                    return Io.Outcome.DONE;
                }

                throw new IOException("unexpected frame 0x" + Integer.toHexString(fr.type & 0xff)
                        + " during " + it.name);
            }
        } finally {
            // A drop mid-file leaves the partial and its committed offset intact,
            // which is exactly what the next connection needs to resume.
            if (keepPartial) out.abandon();
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Drains RESUME requests that arrived between rounds.
     *
     * Anything else on the control queue is put straight back: a PAUSE or CANCEL
     * that lands here belongs to a transfer about to start, and swallowing it
     * would silently lose the request.
     */
    public static List<String> takeResumeRequests(Session s) {
        List<String> ids = new ArrayList<String>();
        List<Wire.Frame> keep = new ArrayList<Wire.Frame>();

        Wire.Frame c = s.pollControl();
        while (c != null) {
            if (c.type == Wire.RESUME) {
                try {
                    ids.add(c.in().readUTF());
                } catch (IOException ignored) { }
            } else {
                keep.add(c);
            }
            c = s.pollControl();
        }
        for (Wire.Frame f : keep) s.pushBackControl(f);
        return ids;
    }
}
