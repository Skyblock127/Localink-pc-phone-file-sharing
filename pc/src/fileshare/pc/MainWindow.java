package fileshare.pc;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

import fileshare.core.Certs;
import fileshare.core.Dest;
import fileshare.core.Hexes;
import fileshare.core.Item;
import fileshare.core.Pairing;

public final class MainWindow extends JFrame {

    private final Vault vault;
    private final Rows model = new Rows();
    private final JTable table = new QueueTable(model);

    private final Ui.Dot dot = new Ui.Dot();
    private final JLabel statusText = Ui.label("", Font.PLAIN, 13, Ui.MUTED);
    private final JLabel queueSummary = Ui.label("", Font.PLAIN, 12, Ui.MUTED);
    private final JLabel activityLine = Ui.label("", Font.PLAIN, 12, Ui.MUTED);

    private final JTextArea log = new JTextArea();
    private final JComboBox<String> defaultDest = new JComboBox<String>();
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton resumeButton;
    private final JButton phoneButton;
    private final JPanel centre = new JPanel(new CardLayout());

    private JScrollPane tableScroll;
    private JScrollPane logScroll;
    private javax.swing.JLayeredPane layers;
    private JPanel bottomPanel;
    private int drawerHeight = 150;

    private Connector connector;

    public MainWindow(Vault vault) {
        super("Localink");
        this.vault = vault;

        java.net.URL ico = MainWindow.class.getResource("/fileshare/pc/logo.png");
        if (ico != null) setIconImage(new ImageIcon(ico).getImage());

        sendButton = Ui.button("Send", true, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { sendSelected(); }
        });
        stopButton = Ui.button("Pause", false, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { confirmPause(); }
        });
        resumeButton = Ui.button("Resume", false, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { sendSelected(); }
        });
        phoneButton = Ui.linkButton("", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { showPhonePanel(); }
        });

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 560));
        setSize(1040, 700);
        setLocationRelativeTo(null);

        final JComponent top = buildCentre();
        bottomPanel = (JPanel) buildFooter();

        // The activity panel floats over the list rather than shrinking it, so
        // pulling it up never makes the table scroll or reflow. Positions are set
        // directly with setBounds, which is why dragging is immediate: there is
        // no layout manager in the loop.
        layers = new javax.swing.JLayeredPane();
        layers.setBackground(Ui.BG);
        layers.setOpaque(true);
        layers.add(top, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(bottomPanel, javax.swing.JLayeredPane.PALETTE_LAYER);

        // A real layout manager, not a resize listener. JLayeredPane has no
        // layout by default, so children keep whatever bounds they were given and
        // are never re-validated -- which is why maximising left part of the
        // window still laid out for the old size.
        layers.setLayout(new java.awt.LayoutManager() {
            @Override public void layoutContainer(java.awt.Container parent) {
                int w = parent.getWidth();
                int h = parent.getHeight();
                if (w <= 0 || h <= 0) return;

                top.setBounds(0, 0, w, h);
                int bh = Math.max(minDrawer(), Math.min(maxDrawer(), drawerHeight));
                drawerHeight = bh;
                bottomPanel.setBounds(0, h - bh, w, bh);

                // Bounds changed, so the subtrees have to lay themselves out too.
                top.validate();
                bottomPanel.validate();
            }

            @Override public java.awt.Dimension preferredLayoutSize(java.awt.Container p) {
                return new java.awt.Dimension(800, 500);
            }

            @Override public java.awt.Dimension minimumLayoutSize(java.awt.Container p) {
                return new java.awt.Dimension(400, 300);
            }

            @Override public void addLayoutComponent(String n, java.awt.Component c) { }

            @Override public void removeLayoutComponent(java.awt.Component c) { }
        });

        JPanel rootPane = new JPanel(new BorderLayout());
        rootPane.setBackground(Ui.BG);
        rootPane.add(buildHeader(), BorderLayout.NORTH);
        rootPane.add(layers, BorderLayout.CENTER);
        setContentPane(rootPane);
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { relayout(); }
        });

        table.getSelectionModel().addListSelectionListener(
                new javax.swing.event.ListSelectionListener() {
                    @Override
                    public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                        if (!e.getValueIsAdjusting()) refresh();
                    }
                });

        installDropTarget();
        installKeys();
        refresh();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        connector = new Connector(vault, new UiListener());
        connector.start();

        if (!vault.hasPairing()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { showPhonePanel(); }
            });
        }
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Ui.CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.LINE),
                BorderFactory.createEmptyBorder(13, 22, 13, 14)));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel title = Ui.label("Localink", Font.BOLD, 17, Ui.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel statusRow = new JPanel();
        statusRow.setOpaque(false);
        statusRow.setLayout(new BoxLayout(statusRow, BoxLayout.X_AXIS));
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusRow.add(dot);
        statusRow.add(Ui.gap(7));
        statusRow.add(statusText);

        left.add(title);
        left.add(Box.createRigidArea(new Dimension(1, 3)));
        left.add(statusRow);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(phoneButton);
        right.add(Ui.linkButton("Settings", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { showSettings(); }
        }));
        right.add(Ui.linkButton("Received", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { openFolder(vault.downloadDir()); }
        }));

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------
    // Table
    // ------------------------------------------------------------------

    private JComponent buildCentre() {
        table.setRowHeight(36);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setFont(Ui.font(Font.PLAIN, 13));
        table.setSelectionBackground(new Color(0xE4E7FE));
        table.setSelectionForeground(Ui.TEXT);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        JTableHeader th = table.getTableHeader();
        th.setReorderingAllowed(false);
        th.setResizingAllowed(false);
        th.setPreferredSize(new Dimension(10, 32));
        th.setDefaultRenderer(new HeaderCell());

        table.getColumnModel().getColumn(Rows.COL_NAME).setPreferredWidth(380);
        table.getColumnModel().getColumn(Rows.COL_SIZE).setPreferredWidth(90);
        table.getColumnModel().getColumn(Rows.COL_DEST).setPreferredWidth(150);
        table.getColumnModel().getColumn(Rows.COL_PROGRESS).setPreferredWidth(280);

        table.getColumnModel().getColumn(Rows.COL_NAME).setCellRenderer(new NameCell(model));
        table.getColumnModel().getColumn(Rows.COL_SIZE).setCellRenderer(new Cell(SwingConstants.CENTER, Ui.MUTED));
        table.getColumnModel().getColumn(Rows.COL_DEST).setCellRenderer(new Cell(SwingConstants.LEFT, Ui.TEXT));
        table.getColumnModel().getColumn(Rows.COL_PROGRESS).setCellRenderer(new BarCell(model));

        tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        tableScroll.getViewport().setBackground(Ui.CARD);
        tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        centre.setBorder(BorderFactory.createEmptyBorder(18, 22, 0, 22));
        centre.setOpaque(false);
        centre.add(new Ui.DropZone(), "empty");
        centre.add(tableScroll, "table");
        return centre;
    }

    // ------------------------------------------------------------------
    // Footer, with a draggable edge above it
    // ------------------------------------------------------------------

    private JPanel buildFooter() {
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(0, 22, 12, 22));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(Ui.label("Destination", Font.PLAIN, 12, Ui.MUTED));
        left.add(Ui.gap(10));

        for (Dest d : Dest.values()) defaultDest.addItem(d.label);
        defaultDest.setSelectedItem(Dest.DOWNLOADS.label);
        defaultDest.setFont(Ui.font(Font.PLAIN, 13));
        defaultDest.setPreferredSize(new Dimension(168, 34));
        defaultDest.setMaximumSize(new Dimension(168, 34));
        defaultDest.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.setAllDest(selectedDefaultDest());
            }
        });
        left.add(defaultDest);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(queueSummary);
        right.add(Ui.gap(14));
        right.add(resumeButton);
        right.add(Ui.gap(8));
        right.add(stopButton);
        right.add(Ui.gap(8));
        right.add(sendButton);

        actions.add(left, BorderLayout.WEST);
        actions.add(right, BorderLayout.EAST);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        log.setForeground(Ui.MUTED);
        log.setBackground(Ui.CARD);
        log.setBorder(BorderFactory.createEmptyBorder(6, 22, 8, 22));

        logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.LINE));
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel strip = new JPanel(new BorderLayout());
        strip.setBackground(Ui.CARD);
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.LINE),
                BorderFactory.createEmptyBorder(5, 22, 5, 22)));
        strip.add(activityLine, BorderLayout.CENTER);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(new Grip(), BorderLayout.NORTH);
        head.add(actions, BorderLayout.CENTER);
        head.add(strip, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Ui.BG);
        bottom.setOpaque(true);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.LINE));
        bottom.add(head, BorderLayout.NORTH);
        bottom.add(logScroll, BorderLayout.CENTER);
        return bottom;
    }

    /** Re-runs the layer layout immediately, for drags. */
    private void relayout() {
        if (layers == null) return;
        layers.doLayout();
        layers.repaint();
    }

    private int minDrawer() {
        // Always leave the action row and one line of activity visible.
        return 92;
    }

    /** Up to the bottom of the column headings, and no further. */
    private int maxDrawer() {
        int headerBottom = 18 + table.getTableHeader().getHeight() + 2;
        return Math.max(minDrawer(), layers.getHeight() - headerBottom);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void installKeys() {
        JComponent rp = getRootPane();
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        bind(rp, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { removeSelected(); }
        });
        bind(rp, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete2", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { removeSelected(); }
        });
        bind(rp, KeyStroke.getKeyStroke(KeyEvent.VK_A, menu), "selectAll", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (model.getRowCount() > 0) {
                    table.requestFocusInWindow();
                    table.selectAll();
                }
            }
        });
        bind(rp, KeyStroke.getKeyStroke(KeyEvent.VK_V, menu), "paste", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { pasteFromClipboard(); }
        });
    }

    private static void bind(JComponent c, KeyStroke ks, String name, AbstractAction action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        c.getActionMap().put(name, action);
    }

    @SuppressWarnings("unchecked")
    private void pasteFromClipboard() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.javaFileListFlavor);
            if (data instanceof List) {
                addAll(new ArrayList<File>((List<File>) data));
                return;
            }
        } catch (Exception e) {
            // Clipboard held something that is not files.
        }
        activity("Clipboard has no files.");
    }

    private void removeSelected() {
        int[] view = table.getSelectedRows();
        if (view.length == 0) return;
        int[] rows = new int[view.length];
        for (int i = 0; i < view.length; i++) rows[i] = table.convertRowIndexToModel(view[i]);

        Set<String> recalled = model.removeRows(rows);
        if (!recalled.isEmpty()) connector.unqueue(recalled);
        refresh();
    }

    private void installDropTarget() {
        TransferHandler h = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    addAll(new ArrayList<File>(files));
                    return true;
                } catch (Exception e) {
                    activity("Could not read that drop.");
                    return false;
                }
            }
        };
        table.setTransferHandler(h);
        centre.setTransferHandler(h);
        ((JComponent) getContentPane()).setTransferHandler(h);
    }

    private Dest selectedDefaultDest() {
        String label = String.valueOf(defaultDest.getSelectedItem());
        for (Dest d : Dest.values()) {
            if (d.label.equals(label)) return d;
        }
        return Dest.DOWNLOADS;
    }

    private void addAll(List<File> files) {
        Dest d = selectedDefaultDest();
        int added = 0;
        for (File f : files) added += model.addRecursive(f, d, 0);
        if (added > 0) activity("Added " + added + (added == 1 ? " file." : " files."));
        refresh();
    }

    private int[] selectedModelRows() {
        int[] view = table.getSelectedRows();
        int[] rows = new int[view.length];
        for (int i = 0; i < view.length; i++) rows[i] = table.convertRowIndexToModel(view[i]);
        return rows;
    }

    /**
     * Sends, or resumes, exactly what is selected -- each row exactly once.
     *
     * Resuming already puts the item back on the queue, so a paused row must not
     * also go through the fresh-queue path. Doing both is what produced several
     * copies of the same file on the phone.
     */
    private void sendSelected() {
        List<Item> fresh = new ArrayList<Item>();

        for (Rows.Row r : model.at(selectedModelRows())) {
            if (!r.sendable()) continue;

            if (r.paused) {
                r.paused = false;
                r.inFlight = true;
                r.status = "Queued";
                model.touch(r);
                connector.resume(r.item.id);
            } else if (!r.inFlight) {
                r.inFlight = true;
                r.status = "Queued";
                model.touch(r);
                fresh.add(r.item);
            }
        }

        if (!fresh.isEmpty()) connector.enqueue(fresh);
        refresh();
    }

    /**
     * Pause what is moving; un-queue what has not started.
     *
     * Arming a pause on a file that has not begun would simply fire the moment it
     * did, which looks like the button doing nothing and then pausing something
     * you never started.
     */
    private void pauseSelected() {
        java.util.Set<String> unqueue = new java.util.HashSet<String>();
        for (Rows.Row r : model.at(selectedModelRows())) {
            if (r.done || !r.inFlight || r.paused) continue;
            if (r.item.id.equals(connector.liveItemId())) {
                connector.pause(r.item.id);
            } else {
                unqueue.add(r.item.id);
                r.inFlight = false;
                r.paused = false;
                r.percent = 0;
                r.doneBytes = 0;
                r.rate = "";
                r.status = "Ready";
                model.touch(r);
            }
        }
        if (!unqueue.isEmpty()) connector.unqueue(unqueue);
        refresh();
    }

    private void confirmPause() {
        int[] sel = selectedModelRows();
        if (sel.length == 0) return;
        int n = 0, live = 0;
        for (Rows.Row r : model.at(sel)) {
            if (r.done || !r.inFlight || r.paused) continue;
            n++;
            if (r.item.id.equals(connector.liveItemId())) live++;
        }
        if (n == 0) return;
        if (live == 0) {          // nothing has started; no confirmation needed
            pauseSelected();
            return;
        }

        int r = JOptionPane.showConfirmDialog(this,
                "Pause " + n + (n == 1 ? " transfer?" : " transfers?")
                        + "\n\nWhat has moved so far is kept, so resuming carries on from there.",
                "Pause", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.YES_OPTION) pauseSelected();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        CardLayout cl = (CardLayout) centre.getLayout();
        cl.show(centre, model.getRowCount() == 0 ? "empty" : "table");

        int[] sel = selectedModelRows();
        int n = model.readyCount();
        queueSummary.setText(n == 0 ? "" : n + (n == 1 ? " file waiting" : " files waiting"));

        // Count what can actually move, not what happens to be highlighted, so
        // selecting a finished file never lights the button up.
        int sendable = 0;
        for (Rows.Row r : model.at(sel)) {
            if (r.sendable()) sendable++;
        }
        sendButton.setEnabled(sendable > 0);
        sendButton.setText(sendable > 1 ? "Send " + sendable : "Send");

        stopButton.setEnabled(model.anyStoppable(sel));
        resumeButton.setVisible(false);

        boolean paired = vault.hasPairing();
        phoneButton.setText(paired ? vault.phoneName() : "Pair a phone");
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showPhonePanel() {
        if (vault.hasPairing()) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            JLabel name = Ui.label(vault.phoneName(), Font.BOLD, 15, Ui.TEXT);
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel fp = Ui.label(Certs.shortFingerprint(vault.phoneFingerprint()),
                    Font.PLAIN, 11, Ui.MUTED);
            fp.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(name);
            p.add(Box.createRigidArea(new Dimension(1, 4)));
            p.add(fp);

            int r = JOptionPane.showOptionDialog(this, p, "Paired phone",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                    new Object[]{"Forget this phone", "Close"}, "Close");
            if (r == 0) {
                connector.unpairAndNotify();
                activity("Pairing removed.");
                refresh();
            }
            return;
        }

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel head = Ui.label("Enter the code shown on the phone", Font.PLAIN, 13, Ui.TEXT);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(head);

        final JTextField field = Ui.codeField();
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createRigidArea(new Dimension(1, 12)));
        p.add(field);

        int r = JOptionPane.showConfirmDialog(this, p, "Pair a phone",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        final String code = field.getText();
        if (!Pairing.looksComplete(code)) return;

        statusText.setText("Pairing");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() { return connector.pair(code); }

            @Override protected void done() {
                String err;
                try { err = get(); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                if (err == null) activity("Paired with " + vault.phoneName() + ".");
                else JOptionPane.showMessageDialog(MainWindow.this, err,
                        "Pairing failed", JOptionPane.ERROR_MESSAGE);
                refresh();
            }
        }.execute();
    }

    private void showSettings() {
        final JTextField name = new JTextField(vault.deviceName(), 20);
        final JTextField dir = new JTextField(vault.downloadDir().getAbsolutePath(), 28);
        final JTextField host = new JTextField(vault.manualHost(), 20);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(labelled("Name", name));
        p.add(labelled("Save received files in", dir));
        p.add(labelled("Phone address override", host));

        JLabel fp = Ui.label("This laptop: " + Certs.shortFingerprint(vault.fingerprint()),
                Font.PLAIN, 11, Ui.MUTED);
        fp.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createRigidArea(new Dimension(1, 12)));
        p.add(fp);

        int r = JOptionPane.showConfirmDialog(this, p, "Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        vault.setDeviceName(name.getText());
        vault.setManualHost(host.getText());
        File d = new File(dir.getText().trim());
        if (d.isDirectory() || d.mkdirs()) vault.setDownloadDir(d);
    }

    private static JPanel labelled(String text, JComponent field) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JLabel l = Ui.label(text, Font.PLAIN, 12, Ui.MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setPreferredSize(new Dimension(360, 30));
        field.setMaximumSize(new Dimension(360, 30));
        p.add(l);
        p.add(Box.createRigidArea(new Dimension(1, 5)));
        p.add(field);
        return p;
    }

    private void openFolder(File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception e) {
            activity("Could not open " + f);
        }
    }

    private void activity(final String line) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                activityLine.setText(line);
                log.append(line + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }
        });
    }

    private void shutdown() {
        if (connector != null) connector.stop();
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------

    private final class UiListener implements Connector.Listener {

        @Override
        public void onStatus(final String text, final boolean connected) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    statusText.setText(text);
                    boolean bad = text.startsWith("Disconnected") || text.contains("not your paired");
                    dot.setColor(connected ? Ui.LIVE : (bad ? Ui.BAD : Ui.IDLE));
                    statusText.setForeground(connected ? Ui.TEXT : Ui.MUTED);
                }
            });
        }

        @Override
        public void onLog(String text) { activity(text); }

        @Override
        public void onProgress(final Item it, final long done, final long total,
                               final long bytesPerSec, final boolean sending) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(it.id);
                    if (row == null) {
                        if (sending) return;
                        row = model.noteIncoming(it);
                    }
                    row.doneBytes = done;
                    row.percent = total <= 0 ? 100 : (int) (done * 100L / total);
                    row.inFlight = true;
                    row.paused = false;
                    row.status = sending ? "Sending" : "Receiving";
                    row.rate = Hexes.humanRate(bytesPerSec);
                    model.touch(row);
                    refresh();
                }
            });
        }

        @Override
        public void onSendResult(final Item it, final boolean ok, final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(it.id);
                    if (row != null) {
                        row.done = ok;
                        row.failed = !ok;
                        row.inFlight = false;
                        row.paused = false;
                        if (ok) {
                            row.percent = 100;
                            row.doneBytes = row.item.size;
                        }
                        row.status = ok ? "Sent" : message;
                        row.rate = "";
                        model.touch(row);
                    }
                    refresh();
                }
            });
        }

        @Override
        public void onReceived(final Item it, final File savedTo) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(it.id);
                    if (row == null) row = model.noteIncoming(it);
                    row.done = true;
                    row.inFlight = false;
                    row.paused = false;
                    row.percent = 100;
                    row.doneBytes = row.item.size;
                    row.status = "Received";
                    row.rate = "";
                    model.touch(row);
                    refresh();
                }
            });
            activity("Received " + it.name);
        }

        @Override
        public void onPaused(final Item it, final long done, final long total, final boolean sending) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(it.id);
                    if (row == null) row = model.noteIncoming(it);
                    row.paused = true;
                    row.inFlight = true;
                    row.doneBytes = done;
                    row.percent = total <= 0 ? 0 : (int) (done * 100L / total);
                    row.rate = "";
                    row.status = "Paused";
                    model.touch(row);
                    refresh();
                }
            });
        }

        @Override
        public void onCancelled(final Item it) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(it.id);
                    if (row != null) {
                        row.paused = false;
                        row.inFlight = false;
                        row.done = false;
                        row.failed = true;
                        row.status = "Cancelled";
                        row.rate = "";
                        model.touch(row);
                    }
                    refresh();
                }
            });
        }

        @Override
        public void onResumed(final String itemId) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    Rows.Row row = model.findById(itemId);
                    if (row != null) {
                        row.paused = false;
                        row.inFlight = true;
                        row.status = "Queued";
                        model.touch(row);
                    }
                    refresh();
                }
            });
        }

        @Override
        public void onUnpairedByPeer() {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { refresh(); }
            });
        }
    }

    // ------------------------------------------------------------------
    // Renderers
    // ------------------------------------------------------------------

    /** The draggable top edge of the activity panel. */
    private final class Grip extends JPanel {
        private int startY;
        private int startHeight;

        Grip() {
            setOpaque(false);
            // Height is the gap above the action row, so the grip adds nothing.
            setPreferredSize(new Dimension(10, 13));
            setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    startY = e.getYOnScreen();
                    startHeight = drawerHeight;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    drawerHeight = startHeight + (startY - e.getYOnScreen());
                    relayout();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xB9BFC8));
            int w = 36;
            g2.fillRoundRect((getWidth() - w) / 2, (getHeight() - 3) / 2, w, 3, 3, 3);
            g2.dispose();
        }
    }

    private static final class QueueTable extends JTable {
        private final Rows rows;

        QueueTable(Rows rows) {
            super(rows);
            this.rows = rows;
        }

        @Override
        public TableCellEditor getCellEditor(int row, int column) {
            if (column == Rows.COL_DEST) {
                JComboBox<String> box = new JComboBox<String>();
                box.setFont(Ui.font(Font.PLAIN, 13));
                for (String s : rows.destOptionsFor(convertRowIndexToModel(row))) box.addItem(s);
                return new DefaultCellEditor(box);
            }
            return super.getCellEditor(row, column);
        }

        @Override
        public Component prepareRenderer(TableCellRenderer r, int row, int col) {
            Component c = super.prepareRenderer(r, row, col);
            if (!isRowSelected(row)) {
                c.setBackground(row % 2 == 0 ? Ui.CARD : Ui.ROW_ALT);
            }
            return c;
        }
    }

    /** Same left inset as the cells, so headings line up with their column. */
    private static final class HeaderCell extends DefaultTableCellRenderer {
        HeaderCell() {
            setFont(Ui.font(Font.BOLD, 11));
            setForeground(Ui.MUTED);
            setBackground(Ui.CARD);
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.LINE),
                    BorderFactory.createEmptyBorder(0, 12, 0, 12)));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            setText(String.valueOf(v));
            return this;
        }
    }

    private static final class Cell extends DefaultTableCellRenderer {
        private final Color colour;

        Cell(int align, Color colour) {
            this.colour = colour;
            setHorizontalAlignment(align);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, false, row, col);
            setForeground(sel ? Ui.TEXT : colour);
            return this;
        }
    }

    /** Direction arrow, then the name, clipped with an ellipsis and a tooltip. */
    private static final class NameCell extends JPanel implements TableCellRenderer {
        private final Rows model;
        private final JLabel arrow = new JLabel();
        private final JLabel name = new JLabel();

        NameCell(Rows model) {
            super(new BorderLayout(6, 0));
            this.model = model;
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            arrow.setFont(Ui.font(Font.BOLD, 13));
            name.setFont(Ui.font(Font.PLAIN, 13));
            add(arrow, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            int mi = t.convertRowIndexToModel(row);
            Rows.Row r = mi >= 0 && mi < model.getRowCount() ? model.rows().get(mi) : null;

            boolean incoming = r != null && r.incoming;
            arrow.setText(incoming ? "↓" : "↑");
            arrow.setForeground(incoming ? Ui.RECV : Ui.SEND);

            String text = String.valueOf(v);
            name.setText(text);
            name.setForeground(Ui.TEXT);
            setToolTipText(text);

            setBackground(sel ? t.getSelectionBackground() : (row % 2 == 0 ? Ui.CARD : Ui.ROW_ALT));
            setOpaque(true);
            return this;
        }
    }

    /**
     * Progress and status in one column.
     *
     * The bar is driven from the byte count rather than a whole-number percent,
     * so it moves as smoothly as the phone's does, and the text on top carries
     * the state, the exact percentage and the current rate.
     */
    private static final class BarCell extends JPanel implements TableCellRenderer {
        private final Rows model;
        private final JProgressBar bar = new JProgressBar(0, 10000);

        BarCell(Rows model) {
            super(new BorderLayout());
            this.model = model;
            setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            bar.setStringPainted(true);
            bar.setFont(Ui.font(Font.PLAIN, 11));
            bar.setForeground(Ui.ACCENT);
            bar.setBackground(new Color(0xE6E9ED));
            bar.setBorderPainted(false);
            add(bar, BorderLayout.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            int mi = t.convertRowIndexToModel(row);
            Rows.Row r = mi >= 0 && mi < model.getRowCount() ? model.rows().get(mi) : null;

            double f = r == null ? 0 : r.fraction();
            bar.setValue((int) Math.round(f * 10000));

            String text;
            if (r == null) {
                text = "";
            } else if (r.done) {
                text = r.label();
            } else if (r.failed) {
                text = r.label();
            } else {
                text = r.label() + "   " + String.format(Locale.US, "%.1f%%", f * 100)
                        + (r.rate.isEmpty() ? "" : "   " + r.rate);
            }
            bar.setString(text);
            bar.setForeground(r != null && r.failed ? Ui.BAD
                    : (r != null && r.paused ? Ui.IDLE : Ui.ACCENT));

            setBackground(sel ? t.getSelectionBackground() : (row % 2 == 0 ? Ui.CARD : Ui.ROW_ALT));
            setOpaque(true);
            return this;
        }
    }
}
