package io.github.henryxjh.mcclientupdate.ui;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import io.github.henryxjh.mcclientupdate.platform.PlatformContext;

/**
 * Shows a Swing dialog listing manual updates, download failures, and install failures.
 * The dialog must be closed before the game can proceed to exit.
 */
public final class UpdateAttentionDialog {

    private UpdateAttentionDialog() {
    }

    /**
     * Displays the attention dialog if the given text is not empty.
     * Blocks until the user closes the dialog, then returns.
     */
    public static void showTextIfNeeded(String text, PlatformContext platform) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Runnable showRunnable = () -> {
            try {
                JTextArea area = new JTextArea(text, 30, 80);
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setCaretPosition(0);
                JScrollPane scrollPane = new JScrollPane(area);
                JOptionPane.showMessageDialog(
                        null,
                        scrollPane,
                        "MC Client Update - Action Required",
                        JOptionPane.WARNING_MESSAGE);
            } catch (Throwable t) {
                platform.log("Unable to show update attention dialog: " + t);
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showRunnable.run();
            } else {
                SwingUtilities.invokeAndWait(showRunnable);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            platform.log("Update attention dialog interrupted");
        } catch (Throwable t) {
            platform.log("Unknown error showing update attention dialog: " + t);
        }
    }
}
