package netgraph.app;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import netgraph.ui.NetworkGraphFrame;

/**
 * Application entry point for Packed Network Graph Explorer.
 */
public final class NetworkGraphExplorer {
    private NetworkGraphExplorer() {
    }

    public static void main(final String[] arguments) {
        configureLookAndFeel();

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final NetworkGraphFrame frame =
                        new NetworkGraphFrame();

                frame.setVisible(true);
            }
        });
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());

        } catch (Exception ignored) {
            /*
             * The application remains fully functional with the platform
             * default Swing look and feel.
             */
        }
    }
}