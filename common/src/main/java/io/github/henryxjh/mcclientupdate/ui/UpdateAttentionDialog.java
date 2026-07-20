package io.github.henryxjh.mcclientupdate.ui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import io.github.henryxjh.mcclientupdate.platform.PlatformContext;

/**
 * Shows a Swing dialog listing manual updates, download failures, and install failures.
 * The dialog will auto-close after {@value #DIALOG_TIMEOUT_MILLIS} milliseconds to avoid
 * blocking the mod loading thread on headless platforms like Android/FCL.
 */
public final class UpdateAttentionDialog {

    /**
     * Maximum time (in milliseconds) to wait for the user to close the dialog.
     */
    public static final long DIALOG_TIMEOUT_MILLIS = 30_000L;

    private UpdateAttentionDialog() {
    }

    /**
     * Displays the attention dialog if the given text is not empty.
     * Blocks until the user closes the dialog or the timeout elapses, then returns.
     */
    public static void showTextIfNeeded(String text, PlatformContext platform) {
        showWithTimeout(text, platform, DIALOG_TIMEOUT_MILLIS);
    }

    /**
     * Displays the attention dialog using the given timeout in milliseconds.
     * Suitable for testing with short timeouts.
     */
    public static void showWithTimeout(String text, PlatformContext platform, long timeoutMillis) {
        if (text == null || text.isEmpty()) {
            return;
        }
        showModalInternal(text, platform, timeoutMillis);
    }

    private static void showModalInternal(String text, PlatformContext platform, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            platform.log("Skipped update attention dialog due to non-positive timeout");
            return;
        }

        // safeguard: ensure we never block the caller for longer than
        // the requested timeout plus a small margin.
        CountDownLatch dialogClosedLatch = new CountDownLatch(1);
        AtomicReference<JDialog> dialogRef = new AtomicReference<>();

        Runnable edtTask = () -> {
            try {
                JTextArea area = new JTextArea(text, 30, 80);
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setCaretPosition(0);
                JScrollPane scrollPane = new JScrollPane(area);

                JOptionPane optionPane = new JOptionPane(
                        scrollPane,
                        JOptionPane.WARNING_MESSAGE);
                JDialog dialog = optionPane.createDialog(
                        null, "MC Client Update - Action Required");
                dialog.setModal(false);   // non‑modal so EDT is not blocked
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialogRef.set(dialog);

                dialog.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        dialogClosedLatch.countDown();
                    }
                });

                Timer autoClose = new Timer((int) timeoutMillis, e -> {
                    // count down before disposing so the latch is released
                    // even if windowClosed is not invoked later.
                    dialogClosedLatch.countDown();
                    dialog.dispose();
                });
                autoClose.setRepeats(false);
                autoClose.start();

                dialog.setVisible(true);
            } catch (LinkageError | RuntimeException ex) {
                platform.log("Swing dialog failed: " + ex);
                dialogClosedLatch.countDown();
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            edtTask.run();
            platform.log("Update attention dialog was invoked on EDT; continuing without waiting");
            return;
        } else {
            SwingUtilities.invokeLater(edtTask);
        }

        try {
            boolean finished = dialogClosedLatch.await(
                    timeoutMillis + 500, TimeUnit.MILLISECONDS);
            if (!finished) {
                JDialog dialog = dialogRef.get();
                if (dialog != null) {
                    SwingUtilities.invokeLater(dialog::dispose);
                }
                platform.log("Update attention dialog timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            platform.log("Update attention dialog interrupted");
        }
    }
}
