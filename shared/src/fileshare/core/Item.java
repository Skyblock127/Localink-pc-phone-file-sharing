package fileshare.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One file in a transfer. Only the fields written by {@link #encode} cross the wire. */
public final class Item {

    /** Stable across sessions so an interrupted transfer can be matched up and resumed. */
    public String id = "";
    public String name = "";
    public long size;
    public int destCode = Dest.DOWNLOADS.code;
    /** Where it came from on the sender, shown on the receiving screen. Display only. */
    public String sourceHint = "";
    public String mime = "application/octet-stream";

    // ---- local only, never transmitted ----

    /** java.io.File on the laptop, content Uri string on the phone. */
    public transient Object handle;
    /** Resume point reported by the receiver in OFFER_REPLY. */
    public transient long have;
    public transient boolean accepted = true;

    public Dest dest() {
        return Dest.byCode(destCode);
    }

    public static void encodeList(Wire.Buf b, List<Item> items) {
        b.i32(items.size());
        for (Item it : items) {
            b.str(it.id)
             .str(it.name)
             .i64(it.size)
             .i32(it.destCode)
             .str(it.sourceHint)
             .str(it.mime);
        }
    }

    public static List<Item> decodeList(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 20000) throw new IOException("implausible file count: " + n);
        List<Item> out = new ArrayList<Item>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            Item it = new Item();
            it.id = in.readUTF();
            // The sender is expected to have sanitized this already; we redo it
            // here because the receiver is the side that pays for getting it wrong.
            it.name = Sanitize.fileName(in.readUTF());
            it.size = in.readLong();
            if (it.size < 0) throw new IOException("negative file size");
            it.destCode = in.readInt();
            it.sourceHint = in.readUTF();
            it.mime = in.readUTF();
            out.add(it);
        }
        return out;
    }

    @Override
    public String toString() {
        return name + " (" + Hexes.humanBytes(size) + ") -> " + dest().label;
    }
}
