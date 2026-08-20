package fileshare.pc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** Shared look for the laptop window: one palette, a few reusable widgets. */
final class Ui {
    private Ui() {}

    static final Color BG        = new Color(0xF6F7F9);
    static final Color CARD      = Color.WHITE;
    static final Color LINE      = new Color(0xE2E5EA);
    static final Color TEXT      = new Color(0x1A1D21);
    static final Color MUTED     = new Color(0x6B7280);
    static final Color ACCENT    = new Color(0x3A51FA);
    static final Color ACCENT_HI = new Color(0x4C61FF);
    static final Color LIVE      = new Color(0x2E9E5B);
    static final Color SEND      = new Color(0xD93A34);
    static final Color RECV      = new Color(0x2E9E5B);
    static final Color IDLE      = new Color(0x9AA1AC);
    static final Color BAD       = new Color(0xC2410C);
    static final Color ROW_ALT   = new Color(0xFAFBFC);

    static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    static JLabel label(String text, int style, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    /** Flat button; {@code primary} paints it as the main action. */
    static JButton button(String text, boolean primary, ActionListener onClick) {
        final JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = getBackground();
                if (!isEnabled()) {
                    fill = primary ? new Color(0xC7CDD4) : new Color(0xF0F2F4);
                } else if (getModel().isPressed()) {
                    fill = fill.darker();
                } else if (getModel().isRollover()) {
                    fill = primary ? ACCENT_HI : new Color(0xF0F2F4);
                }
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                if (!primary) {
                    g2.setColor(LINE);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(font(primary ? Font.BOLD : Font.PLAIN, 13));
        b.setForeground(primary ? Color.WHITE : TEXT);
        b.setBackground(primary ? ACCENT : CARD);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        if (onClick != null) b.addActionListener(onClick);
        return b;
    }

    /** Small text-only button for secondary actions in the header. */
    static JButton linkButton(String text, ActionListener onClick) {
        final JButton b = new JButton(text);
        b.setFont(font(Font.PLAIN, 12));
        b.setForeground(MUTED);
        b.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setForeground(TEXT); }
            @Override public void mouseExited(MouseEvent e) { b.setForeground(MUTED); }
        });
        if (onClick != null) b.addActionListener(onClick);
        return b;
    }

    /**
     * Field for the pairing code.
     *
     * Nothing invalid can be typed into it: characters outside the code alphabet
     * are dropped, lower case is folded up, the length is capped, and the dash is
     * inserted after the fourth character on its own. That removes every question
     * the user would otherwise have to ask -- whether to type the dash, whether
     * case matters, whether the spaces they pasted are a problem.
     */
    static JTextField codeField() {
        final JTextField f = new JTextField();
        f.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
        f.setHorizontalAlignment(SwingConstants.CENTER);
        f.setMaximumSize(new Dimension(300, 48));
        f.setPreferredSize(new Dimension(300, 48));

        // Take focus as soon as the dialog appears. Without this the first
        // keystrokes go to the default button instead of the field, and a typed
        // space submits the dialog before anything has been entered.
        f.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                f.requestFocusInWindow();
            }
            @Override public void ancestorRemoved(javax.swing.event.AncestorEvent e) { }
            @Override public void ancestorMoved(javax.swing.event.AncestorEvent e) { }
        });

        ((AbstractDocument) f.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int off, String text, AttributeSet a)
                    throws BadLocationException {
                replace(fb, off, 0, text, a);
            }

            @Override
            public void remove(FilterBypass fb, int off, int len) throws BadLocationException {
                // Backspacing onto the dash should eat the character before it,
                // otherwise the dash reappears and the key press does nothing.
                String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                if (len == 1 && off < cur.length() && cur.charAt(off) == '-' && off > 0) {
                    off--;
                    len = 2;
                }
                replace(fb, off, len, "", null);
            }

            @Override
            public void replace(FilterBypass fb, int off, int len, String text, AttributeSet a)
                    throws BadLocationException {
                String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                String merged = cur.substring(0, off)
                        + (text == null ? "" : text)
                        + cur.substring(Math.min(cur.length(), off + len));

                fb.replace(0, fb.getDocument().getLength(),
                        fileshare.core.Pairing.format(merged), a);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        f.setCaretPosition(f.getDocument().getLength());
                    }
                });
            }
        });
        return f;
    }

    static JPanel card() {
        JPanel p = new JPanel();
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createLineBorder(LINE));
        return p;
    }

    static JPanel row() {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        return p;
    }

    static Component gap(int w) {
        return javax.swing.Box.createRigidArea(new Dimension(w, 1));
    }

    /** A coloured dot, for connection state. */
    static final class Dot extends JComponent {
        private Color color = IDLE;

        Dot() {
            setPreferredSize(new Dimension(10, 10));
        }

        void setColor(Color c) {
            color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(0, 1, 9, 9);
            g2.dispose();
        }
    }

    /** Empty-state panel with a dashed border, shown when the queue is empty. */
    static final class DropZone extends JPanel {
        DropZone() {
            setOpaque(false);
            setLayout(new java.awt.GridBagLayout());

            JPanel stack = new JPanel();
            stack.setOpaque(false);
            stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));

            JLabel big = label("Drop files here", Font.BOLD, 18, MUTED);
            big.setAlignmentX(CENTER_ALIGNMENT);
            stack.add(big);
            add(stack);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xCED4DB));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{7f, 6f}, 0f));
            g2.drawRoundRect(14, 14, getWidth() - 29, getHeight() - 29, 16, 16);
            g2.dispose();
        }
    }
}
