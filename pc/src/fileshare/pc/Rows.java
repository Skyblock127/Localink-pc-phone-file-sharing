package fileshare.pc;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.table.AbstractTableModel;

import fileshare.core.Dest;
import fileshare.core.Hexes;
import fileshare.core.Item;
import fileshare.core.Sanitize;

/** The transfer list: files going out, and files coming in. */
public final class Rows extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_SIZE = 1;
    public static final int COL_DEST = 2;
    public static final int COL_PROGRESS = 3;

    private static final String[] HEADERS = {"File", "Size", "Destination", "Progress"};

    public static final class Row {
        public final Item item;
        public final boolean incoming;
        public String status;
        public int percent;
        public boolean inFlight;
        public boolean done;
        public boolean failed;
        public boolean paused;
        public long doneBytes;
        public String rate = "";

        /** Exact percentage, so the bar is not quantised to whole numbers. */
        public double fraction() {
            if (item.size <= 0) return done ? 1 : 0;
            return Math.max(0, Math.min(1, doneBytes / (double) item.size));
        }

        Row(Item item, boolean incoming) {
            this.item = item;
            this.incoming = incoming;
            this.status = incoming ? "Incoming" : "Ready";
        }

        /** Queued for sending but not yet started, so it can still be pulled back. */
        public boolean recallable() {
            return !incoming && !done && !failed;
        }

        /** One line of state for the merged progress column. */
        public String label() {
            if (done) return incoming ? "Received" : "Sent";
            if (failed) return status;
            if (paused) return "Paused";
            if (!inFlight) return "Ready";
            return status;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();

    public List<Row> rows() { return rows; }

    public Row findById(String id) {
        for (Row r : rows) {
            if (r.item.id.equals(id)) return r;
        }
        return null;
    }

    // ---- outgoing ------------------------------------------------------

    public boolean add(File f, Dest defaultDest) {
        if (f == null || !f.isFile()) return false;

        Item it = new Item();
        it.id = PcStore.idFor(f);
        for (Row r : rows) {
            if (r.item.id.equals(it.id)) return false;
        }
        it.name = Sanitize.fileName(f.getName());
        it.size = f.length();
        it.mime = Mimes.of(f.getName());
        it.sourceHint = f.getParent() == null ? "" : f.getParent();
        it.handle = f;
        it.destCode = defaultDest.accepts(it.mime) ? defaultDest.code : Dest.DOWNLOADS.code;

        rows.add(new Row(it, false));
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        return true;
    }


    /** Folders are flattened: sender-supplied subpaths would put a path back on the wire. */
    public int addRecursive(File f, Dest defaultDest, int depth) {
        if (depth > 12 || f == null) return 0;
        if (f.isFile()) return add(f, defaultDest) ? 1 : 0;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) n += addRecursive(k, defaultDest, depth + 1);
        }
        return n;
    }

    /** Shows a file the phone is sending, so it is visible while it arrives. */
    public Row noteIncoming(Item it) {
        Row existing = findById(it.id);
        if (existing != null) return existing;
        Row r = new Row(it, true);
        r.inFlight = true;
        rows.add(r);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        return r;
    }

    /**
     * Removes the given rows.
     *
     * @return ids that were already handed to the connector, so the caller can
     *         pull them back out of the send queue.
     */
    public java.util.Set<String> removeRows(int[] modelRows) {
        java.util.Set<String> recalled = new HashSet<String>();
        List<Row> doomed = new ArrayList<Row>();
        for (int i : modelRows) {
            if (i < 0 || i >= rows.size()) continue;
            Row r = rows.get(i);
            if (r.incoming && r.inFlight && !r.done && !r.paused) continue;  // stop it first
            doomed.add(r);
            if (r.recallable()) recalled.add(r.item.id);
        }
        rows.removeAll(doomed);
        fireTableDataChanged();
        return recalled;
    }

    public List<Row> at(int[] modelRows) {
        List<Row> out = new ArrayList<Row>();
        for (int i : modelRows) {
            if (i >= 0 && i < rows.size()) out.add(rows.get(i));
        }
        return out;
    }

    /** Files waiting to be sent, for the summary line. */
    public int readyCount() {
        int n = 0;
        for (Row r : rows) {
            if (!r.incoming && !r.done && (!r.inFlight || r.paused)) n++;
        }
        return n;
    }

    /** True if any of these rows is moving or paused, so Pause applies. */
    public boolean anyStoppable(int[] modelRows) {
        for (Row r : at(modelRows)) {
            if (!r.done && r.inFlight) return true;
        }
        return false;
    }

    public Row firstPaused() {
        for (Row r : rows) {
            if (r.paused && !r.done) return r;
        }
        return null;
    }

    public void setAllDest(Dest d) {
        for (Row r : rows) {
            if (r.incoming || r.inFlight || r.done) continue;
            if (d.accepts(r.item.mime)) r.item.destCode = d.code;
        }
        fireTableDataChanged();
    }

    public void touch(Row r) {
        int i = rows.indexOf(r);
        if (i >= 0) fireTableRowsUpdated(i, i);
    }

    // ---- TableModel ----------------------------------------------------

    @Override public int getRowCount() { return rows.size(); }

    @Override public int getColumnCount() { return HEADERS.length; }

    @Override public String getColumnName(int c) { return HEADERS[c]; }

    @Override
    public Class<?> getColumnClass(int c) {
        return c == COL_PROGRESS ? Integer.class : String.class;
    }

    @Override
    public boolean isCellEditable(int r, int c) {
        Row row = rows.get(r);
        return c == COL_DEST && !row.incoming && !row.inFlight && !row.done;
    }

    @Override
    public Object getValueAt(int r, int c) {
        Row row = rows.get(r);
        switch (c) {
            case COL_NAME:     return row.item.name;
            case COL_SIZE:     return Hexes.humanBytes(row.item.size);
            case COL_DEST:     return row.incoming ? "This PC" : row.item.dest().label;
            case COL_PROGRESS: return Integer.valueOf(row.percent);
            default:           return "";
        }
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        if (c != COL_DEST) return;
        Row row = rows.get(r);
        for (Dest d : Dest.values()) {
            if (d.label.equals(String.valueOf(v)) && d.accepts(row.item.mime)) {
                row.item.destCode = d.code;
                fireTableRowsUpdated(r, r);
                return;
            }
        }
    }

    public List<String> destOptionsFor(int r) {
        List<String> out = new ArrayList<String>();
        if (rows.get(r).incoming) {
            out.add("This PC");
            return out;
        }
        for (Dest d : Dest.values()) {
            if (d.accepts(rows.get(r).item.mime)) out.add(d.label);
        }
        return out;
    }
}
