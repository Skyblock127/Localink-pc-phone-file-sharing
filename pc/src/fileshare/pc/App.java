package fileshare.pc;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class App {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Cross-platform look and feel is a fine fallback.
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Vault vault = new Vault();
                    new MainWindow(vault).setVisible(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null,
                            "Localink could not start.\n\n" + e,
                            "Localink", JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                }
            }
        });
    }
}

