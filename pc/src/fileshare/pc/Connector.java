package fileshare.pc;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Session;
import fileshare.core.Tls;
import fileshare.core.Transfer;
import fileshare.core.TransferState;
import fileshare.core.Wire;

/**
 * Owns the connection to the phone and the round loop.
 *
 * Runs on its own thread. Everything reported to the window goes through
 * {@link Listener}, which marshals onto the Swing thread.
 */
public final class Connector {

    public interface Listener {
        void onStatus(String text, boolean connected);

        void onLog(String text);

        void onProgress(Item it, long done, long total, long bytesPerSec, boolean sending);

        void onSendResult(Item it, boolean ok, String message);

        void onReceived(Item it, File savedTo);

        void onPaused(Item it, long done, long total, boolean sending);

        void onCancelled(Item it);

        void onResumed(String itemId);

        void onUnpairedByPeer();
    }

    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final long IDLE_PAUSE_MS = 600;

    private final Vault vault;
    private final Listener listener;
    private final Io.Source source = new PcStore.Source();
    private final TransferState state = new TransferState();

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Session session;

    private final Object lock = new Object();
    private List<Item> pending = new ArrayList<Item>();

    /** Every id that is queued or in flight, so nothing is ever queued twice. */
    private final java.util.Set<String> queued =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Paused items, held so Resume can put them straight back in the queue. */
    private final Map<String, Item> pausedItems =
            Collections.synchronizedMap(new LinkedHashMap<String, Item>());

    private volatile String currentItemId;
    private long backoffMs = 0;

    public Connector(Vault vault, Listener listener) {
        this.vault = vault;
        this.listener = listener;
    }

    // ------------------------------------------------------------------

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "localink-connector");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Session s = session;
        if (s != null) s.closeQuietly();
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public boolean isConnected() { return session != null; }

    /**
     * Adds to the queue, ignoring anything already queued or moving.
     *
     * A file must never appear twice: the receiver would take it twice and write
     * two copies. This is the backstop for that; callers are expected not to try.
     */
    /**
     * Adds to the queue, once.
     *
     * The guard is a set of ids covering both the waiting list and the batch
     * currently in flight. Checking {@code pending} alone was not enough: a round
     * moves the batch out of {@code pending} before sending it, so for the whole
     * duration of a transfer the same file looked un-queued and could be added
     * again -- and the receiver would then write it twice.
     */
    public void enqueue(List<Item> items) {
        synchronized (lock) {
            for (Item it : items) {
                if (!queued.add(it.id)) {
                    // Should not happen; logged because a silent duplicate here
                    // means the receiver writes the file twice.
                    listener.onLog("Already queued, ignoring: " + it.name);
                    continue;
                }
                pending.add(it);
            }
        }
    }

    public void dropQueued(Set<String> ids) {
        synchronized (lock) {
            List<Item> keep = new ArrayList<Item>();
            for (Item it : pending) {
                if (!ids.contains(it.id)) keep.add(it);
            }
            pending = keep;
        }
        for (String id : ids) {
            queued.remove(id);
            pausedItems.remove(id);
            state.forget(id);
        }
    }

    public void unqueue(Set<String> ids) { dropQueued(ids); }

    // ------------------------------------------------------------------
    // Pause / resume / cancel
    // ------------------------------------------------------------------

    public String liveItemId() { return currentItemId; }

    /**
     * Pause a file.
     *
     * If it is moving, the engine stops it between chunks and both ends keep
     * what they have. If it is only queued, it leaves the queue immediately --
     * arming a pause on something that has not started would just fire the
     * instant it did.
     */
    public void pause(String id) {
        if (id == null) return;
        if (id.equals(currentItemId)) {
            state.requestPause(id);
            return;
        }
        Item taken = null;
        synchronized (lock) {
            List<Item> keep = new ArrayList<Item>();
            for (Item it : pending) {
                if (it.id.equals(id)) taken = it;
                else keep.add(it);
            }
            pending = keep;
        }
        if (taken != null) {
            pausedItems.put(id, taken);
            listener.onPaused(taken, 0, taken.size, true);
        }
    }

    /**
     * Put a paused file back at the end of the queue, here and on the phone.
     *
     * The queue is the only thing that decides what gets offered, so this is all
     * resuming has to mean.
     */
    public void resume(String id) {
        if (id == null) return;
        Item back = pausedItems.remove(id);
        if (back != null) {
            state.noteAccepted(id);
            enqueue(Collections.singletonList(back));
        }
        Session s = session;
        if (s != null) s.requestResume(id);
        listener.onResumed(id);
    }

    /** Drop a file entirely. Works whether it is moving, paused or queued. */
    public void cancel(String id) {
        if (id == null) return;
        if (id.equals(currentItemId)) {
            state.requestCancel(id);
            return;
        }
        state.forget(id);
        pausedItems.remove(id);
        dropQueued(Collections.singleton(id));
    }

    // ------------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                if (session == null) {
                    if (backoffMs > 0) {
                        Thread.sleep(backoffMs);
                        if (!running) return;
                    }
                    connect();
                    if (session == null) {
                        backoffMs = Math.min(5000, backoffMs == 0 ? 1500 : backoffMs + 1000);
                        continue;
                    }
                    backoffMs = 0;
                }
                round();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Transfer.Unpaired e) {
                String fp = vault.phoneFingerprint();
                if (fp != null) vault.forget(fp);
                dropConnection(null);
                listener.onLog("The phone removed this pairing.");
                listener.onUnpairedByPeer();
                listener.onStatus("Not paired", false);
            } catch (IOException e) {
                dropConnection(e.getMessage());
            } catch (Exception e) {
                dropConnection(String.valueOf(e));
            }
        }
        dropConnection(null);
    }

    private void dropConnection(String why) {
        Session s = session;
        session = null;
        currentItemId = null;
        if (s != null) s.closeQuietly();
        if (!running) return;
        listener.onStatus(why == null ? "Not connected" : "Disconnected: " + why, false);
    }

    /**
     * Dial every gateway candidate at once and keep the first that authenticates.
     *
     * Racing them is what makes hotspot and USB tether interchangeable: with both
     * up there are two live paths on different subnets and no reliable way to
     * know which one Windows prefers.
     */
    private void connect() {
        String pin = vault.phoneFingerprint();
        if (pin == null) {
            listener.onStatus("Not paired", false);
            sleepQuietly(1500);
            return;
        }

        List<InetAddress> targets = Gateways.candidates(vault.manualHost());
        if (targets.isEmpty()) {
            listener.onStatus("No network. Connect to your phone's hotspot.", false);
            return;
        }

        listener.onStatus("Looking for " + safeName(vault.phoneName()), false);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, targets.size()));
        List<Future<Session>> futures = new ArrayList<Future<Session>>();
        try {
            final SSLContext ctx = Tls.context(vault.key(), vault.cert());
            for (final InetAddress addr : targets) {
                final String fpin = pin;
                futures.add(pool.submit(new Callable<Session>() {
                    @Override public Session call() throws Exception {
                        return Session.dial(addr, Session.PORT, CONNECT_TIMEOUT_MS,
                                ctx, vault.deviceName(), fpin, null, vault);
                    }
                }));
            }

            Session winner = null;
            String lastError = null;
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS + 2500;
            while (System.currentTimeMillis() < deadline && winner == null) {
                boolean anyPending = false;
                for (Future<Session> f : futures) {
                    if (!f.isDone()) { anyPending = true; continue; }
                    try {
                        Session s = f.get();
                        if (s != null && winner == null) winner = s;
                        else if (s != null) s.closeQuietly();
                    } catch (Exception e) {
                        Throwable c = e.getCause() == null ? e : e.getCause();
                        String m = c.getMessage();
                        if (m != null && m.contains("not your paired")) lastError = m;
                    }
                }
                if (winner != null || !anyPending) break;
                Thread.sleep(40);
            }

            for (Future<Session> f : futures) {
                if (f.isCancelled()) continue;
                if (!f.isDone()) { f.cancel(true); continue; }
                try {
                    Session s = f.get();
                    if (s != null && s != winner) s.closeQuietly();
                } catch (Exception ignored) { }
            }

            if (winner != null) {
                session = winner;
                listener.onStatus("Connected to " + safeName(winner.peerName), true);
                listener.onLog("Connected to " + safeName(winner.peerName)
                        + " at " + winner.sock.getInetAddress().getHostAddress());
            } else if (lastError != null) {
                listener.onStatus(lastError, false);
            } else {
                listener.onStatus("Phone not answering. Is Localink open and unlocked?", false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            listener.onStatus("Cannot start TLS: " + e.getMessage(), false);
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        }
    }

    // ------------------------------------------------------------------

    private void round() throws IOException, InterruptedException {
        final Session s = session;
        if (s == null) return;

        for (String id : Transfer.takeResumeRequests(s)) {
            Item back = pausedItems.remove(id);
            if (back != null) {
                state.noteAccepted(id);
                enqueue(Collections.singletonList(back));
            }
            listener.onResumed(id);
        }

        List<Item> batch;
        synchronized (lock) {
            batch = pending;
            pending = new ArrayList<Item>();
        }
        if (!batch.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Item it : batch) {
                if (names.length() > 0) names.append(", ");
                names.append(it.name);
            }
            listener.onLog("Offering " + batch.size() + ": " + names);
        }

        // Anything that reaches a conclusion this round must not go back on the
        // queue if the connection drops later in the same round. Putting the
        // whole batch back is what made completed files send again and again.
        final java.util.Set<String> settled =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        final boolean[] sending = {true};
        Io.Events events = new Io.Events() {
            @Override public void onStart(Item it, long from, boolean snd) {
                currentItemId = it.id;
                if (!snd) listener.onProgress(it, from, it.size, 0, false);
                if (from > 0) {
                    listener.onLog((snd ? "Sending " : "Receiving ") + it.name
                            + ", resuming from " + Hexes.humanBytes(from));
                }
            }

            @Override public void onBytes(Item it, long done, long total, long bps) {
                currentItemId = it.id;
                listener.onProgress(it, done, total, bps, snd());
            }

            @Override public void onDone(Item it, boolean ok, String msg) {
                currentItemId = null;
                settled.add(it.id);
                queued.remove(it.id);
                state.forget(it.id);
                if (snd()) listener.onSendResult(it, ok, msg);
                else if (ok) listener.onReceived(it, new File(vault.downloadDir(), it.name));
                else listener.onLog("Failed: " + it.name + " - " + msg);
            }

            @Override public void onPaused(Item it, long done, long total, boolean snd) {
                currentItemId = null;
                settled.add(it.id);
                queued.remove(it.id);
                if (snd) pausedItems.put(it.id, it);
                listener.onPaused(it, done, total, snd);
                listener.onLog("Paused " + it.name + " at " + Hexes.humanBytes(done));
            }

            @Override public void onCancelled(Item it) {
                currentItemId = null;
                settled.add(it.id);
                queued.remove(it.id);
                state.forget(it.id);
                pausedItems.remove(it.id);
                listener.onCancelled(it);
                listener.onLog("Cancelled " + it.name);
            }

            @Override public void log(String m) { listener.onLog(m); }

            private boolean snd() { return sending[0]; }
        };

        try {
            sending[0] = true;
            Map<String, Io.Outcome> res =
                    Transfer.sendRound(s, batch, source, events, state.control);

            for (Item it : batch) {
                Io.Outcome o = res.get(it.id);
                if (o == Io.Outcome.REJECTED) {
                    // The row was already marked when the reply came in; this only
                    // tidies the queue bookkeeping.
                    settled.add(it.id);
                    queued.remove(it.id);
                } else if (o == Io.Outcome.DONE) {
                    settled.add(it.id);
                    queued.remove(it.id);
                    state.forget(it.id);
                }
            }
        } catch (IOException e) {
            // Whatever did not finish goes back, so the next connection resumes
            // rather than silently dropping the rest of the queue.
            List<Item> back = new ArrayList<Item>();
            for (Item it : batch) {
                if (settled.contains(it.id)) continue;          // already finished
                if (pausedItems.containsKey(it.id)) continue;   // deliberately held
                back.add(it);
            }
            synchronized (lock) {
                back.addAll(pending);
                pending = back;
            }
            throw e;
        }

        final PcStore.Sink sink = new PcStore.Sink(vault.downloadDir(), true);
        sending[0] = false;
        Map<String, Io.Outcome> got =
                Transfer.receiveRound(s, sink, ACCEPT_ALL, events, state.control);

        if (batch.isEmpty() && got.isEmpty()) {
            Thread.sleep(IDLE_PAUSE_MS);
        }
    }

    /**
     * The laptop takes whatever its own paired phone sends.
     *
     * No prompt here on purpose: the peer has proved it holds the pinned key, the
     * destination is a fixed folder, and being asked twice for every file you
     * deliberately shared is how people learn to click yes without reading.
     */
    private final Io.Approver ACCEPT_ALL = new Io.Approver() {
        @Override public boolean[] decide(List<Item> offered) {
            boolean[] all = new boolean[offered.size()];
            java.util.Arrays.fill(all, true);
            return all;
        }
    };

    // ------------------------------------------------------------------

    public String pair(String code) {
        List<InetAddress> targets = Gateways.candidates(vault.manualHost());
        if (targets.isEmpty()) {
            return "No network found.\n\nConnect this laptop to your phone's hotspot first.";
        }

        StringBuilder tried = new StringBuilder();
        for (InetAddress a : targets) {
            if (tried.length() > 0) tried.append(", ");
            tried.append(a.getHostAddress());
        }
        listener.onLog("Pairing: trying " + tried);

        boolean answered = false;
        String detail = null;

        for (InetAddress addr : targets) {
            String where = addr.getHostAddress();
            try {
                SSLContext ctx = Tls.context(vault.key(), vault.cert());
                Session s = Session.dial(addr, Session.PORT, CONNECT_TIMEOUT_MS,
                        ctx, vault.deviceName(), null, code, vault);
                String name = s.peerName;
                s.bye();
                s.closeQuietly();
                listener.onLog("Paired with " + safeName(name));
                return null;
            } catch (java.net.ConnectException e) {
                listener.onLog("  " + where + ": nothing listening");
            } catch (java.net.SocketTimeoutException e) {
                listener.onLog("  " + where + ": timed out");
            } catch (java.net.NoRouteToHostException e) {
                listener.onLog("  " + where + ": no route");
            } catch (Exception e) {
                answered = true;
                String m = e.getMessage();
                if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
                listener.onLog("  " + where + ": " + m);
                if (m.contains("code did not match") || m.contains("Wrong pairing code")) {
                    return "The phone rejected that code.\n\nCodes are single use and last "
                            + "90 seconds. Generate a new one on the phone.";
                }
                detail = m;
            }
        }

        if (answered) {
            return "The phone answered but the handshake failed.\n\n"
                    + (detail == null ? "" : detail + "\n\n")
                    + "Check the activity list on the phone.";
        }
        return "Could not reach the phone.\n\nCheck this laptop is on the phone's hotspot "
                + "and Localink is open and unlocked.\n\nTried: " + tried;
    }

    /** Drop the pairing, telling the phone first if we are connected. */
    public void unpairAndNotify() {
        final Session s = session;
        session = null;

        String fp = vault.phoneFingerprint();
        if (fp != null) vault.forget(fp);
        synchronized (lock) { pending = new ArrayList<Item>(); }
        pausedItems.clear();
        queued.clear();
        state.clear();
        listener.onStatus("Not paired", false);

        if (s == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    s.send(Wire.UNPAIR);
                } catch (IOException ignored) { }
                s.closeQuietly();
            }
        }, "unpair-notify").start();
    }

    private static String safeName(String n) {
        if (n == null || n.trim().isEmpty()) return "phone";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length() && i < 40; i++) {
            char c = n.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString().trim();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
