package io.github.henryxjh.mcclientupdate.ui;

import io.github.henryxjh.mcclientupdate.platform.PlatformContext;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Dedicated progress display thread with real-time log output and Swing window.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   UpdateProgressDisplay.start(platform);
 *   try {
 *       UpdateProgressDisplay.downloadPhaseStart(4, 21_000_000);
 *       UpdateProgressDisplay.itemStart("mod.jar", 5_000_000, "direct (modrinth)");
 *       // ... during download loop ...
 *       UpdateProgressDisplay.downloadProgress(bytesRead);
 *       UpdateProgressDisplay.itemOk("sha256 verified");
 *       UpdateProgressDisplay.downloadPhaseDone(3, 1, 1);
 *   } finally {
 *       UpdateProgressDisplay.stop();
 *   }
 * }</pre>
 */
public final class UpdateProgressDisplay {

    // ---- Event types --------------------------------------------------

    private enum Phase {DOWNLOAD, INSTALL}

    private sealed interface Event
            permits PhaseStart, ItemStart, ProgressTick, ItemDone, PhaseDone,
                    ItemUrl, ItemModIds, Shutdown {}

    private record PhaseStart(Phase phase, int totalItems, long totalBytes) implements Event {}
    private record ItemStart(String name, long totalBytes, String extra) implements Event {}
    private record ProgressTick(long bytesRead) implements Event {}
    private record ItemDone(boolean success, String message) implements Event {}
    private record PhaseDone(int ok, int failed, int manual, long durationMs) implements Event {}
    private record ItemUrl(String url) implements Event {}
    private record ItemModIds(List<String> modIds) implements Event {}
    private record Shutdown() implements Event {}

    // ---- Internal state -----------------------------------------------

    private static final BlockingQueue<Event> QUEUE = new LinkedBlockingQueue<>();
    private static final long POLL_TIMEOUT_MS = 200;
    private static final long PROGRESS_LOG_INTERVAL_MS = 500;

    private static volatile boolean running;
    private static Thread displayThread;
    private static PlatformContext platform;

    // Mutable state owned by the display thread
    private static Phase currentPhase;
    private static int itemIndex;
    private static int totalItems;
    private static long phaseStartMs;
    private static String currentItemName;
    private static long currentItemTotalBytes;
    private static long lastProgressLogMs;

    // Speed tracking
    private static long lastSpeedSampleBytes;
    private static long lastSpeedSampleMs;
    private static String currentSpeedText = "";

    // Per-phase counters for totals display
    private static int doneCount;
    private static int failCount;
    private static int manualCount;

    // Per-item display state
    private static String currentModIds = "";
    private static String currentUrl = "";
    private static String currentExtra = "";

    // Swing window
    private static boolean swingAvailable;
    private static JDialog swingDialog;
    private static JLabel swingTitleLabel;
    private static JProgressBar swingProgressBar;
    private static JLabel swingFileLabel;
    private static JLabel swingSizeSpeedLabel;
    private static JLabel swingModsLabel;
    private static JLabel swingSourceLabel;
    private static JLabel swingUrlLabel;
    private static JLabel swingTotalLabel;

    // ---- Public API ---------------------------------------------------

    /**
     * Starts the progress display thread. Must be called before any report
     * methods.
     */
    public static void start(PlatformContext ctx) {
        Objects.requireNonNull(ctx, "platform");
        platform = ctx;
        running = true;

        // Try to create Swing window; degrade gracefully on any failure
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                SwingUtilities.invokeAndWait(UpdateProgressDisplay::createSwingWindow);
                swingAvailable = true;
            } catch (Exception e) {
                platform.log("Swing window unavailable, log-only mode: " + e);
                swingAvailable = false;
            }
        }

        displayThread = new Thread(UpdateProgressDisplay::eventLoop,
                "mc-client-update-progress");
        displayThread.setDaemon(true);
        displayThread.start();
    }

    /**
     * Signals the display thread to shut down and waits for it to finish.
     * Safe to call even if not started.
     */
    public static void stop() {
        if (!running) {
            return;
        }
        QUEUE.offer(new Shutdown());
        running = false;
        try {
            displayThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Drain any remaining events so log output is complete
        Event event;
        while ((event = QUEUE.poll()) != null) {
            processEvent(event);
        }
        // Dispose Swing window
        disposeSwingWindow();
    }

    // ---- Phase lifecycle ----------------------------------------------

    public static void downloadPhaseStart(int totalFiles, long totalBytes) {
        QUEUE.offer(new PhaseStart(Phase.DOWNLOAD, totalFiles, totalBytes));
    }

    public static void installPhaseStart(int totalItems) {
        QUEUE.offer(new PhaseStart(Phase.INSTALL, totalItems, 0));
    }

    // ---- Per-item lifecycle -------------------------------------------

    /**
     * Signals the start of processing an item.
     *
     * @param name       file name or mod id
     * @param totalBytes file size in bytes (0 for install/delete items)
     * @param extra      source info for downloads (e.g. "direct (github)"),
     *                   or action description for installs (e.g. "REPLACE, hash verified")
     */
    public static void itemStart(String name, long totalBytes, String extra) {
        QUEUE.offer(new ItemStart(name, totalBytes, extra));
    }

    /**
     * Reports cumulative bytes read for the current download item.
     * Called periodically during file download.
     */
    public static void downloadProgress(long bytesRead) {
        QUEUE.offer(new ProgressTick(bytesRead));
    }

    /**
     * Reports successful completion of the current item.
     *
     * @param message human-readable result, e.g. "downloaded, sha256 verified"
     */
    public static void itemOk(String message) {
        QUEUE.offer(new ItemDone(true, message));
    }

    /**
     * Reports failure of the current item.
     *
     * @param reason human-readable reason, e.g. "HTTP 403"
     */
    public static void itemFail(String reason) {
        QUEUE.offer(new ItemDone(false, reason));
    }

    /**
     * Reports the mod IDs associated with the current download item.
     */
    public static void reportModIds(List<String> modIds) {
        QUEUE.offer(new ItemModIds(List.copyOf(modIds)));
    }

    /**
     * Reports the download URL for the current item.
     */
    public static void reportDownloadUrl(String url) {
        QUEUE.offer(new ItemUrl(url));
    }

    // ---- Phase summary ------------------------------------------------

    /**
     * @param manual count of manual-update items in this phase
     */
    public static void downloadPhaseDone(int ok, int failed, int manual) {
        long duration = System.currentTimeMillis() - phaseStartMs;
        QUEUE.offer(new PhaseDone(ok, failed, manual, duration));
    }

    public static void installPhaseDone(int ok, int failed) {
        long duration = System.currentTimeMillis() - phaseStartMs;
        QUEUE.offer(new PhaseDone(ok, failed, 0, duration));
    }

    // ---- Event loop (runs on display thread) --------------------------

    private static void eventLoop() {
        while (running) {
            Event event;
            try {
                event = QUEUE.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event != null) {
                processEvent(event);
            }
        }
    }

    private static void processEvent(Event event) {
        if (event instanceof PhaseStart ps) {
            handlePhaseStart(ps);
        } else if (event instanceof ItemStart is) {
            handleItemStart(is);
        } else if (event instanceof ProgressTick pt) {
            handleProgressTick(pt);
        } else if (event instanceof ItemDone id) {
            handleItemDone(id);
        } else if (event instanceof PhaseDone pd) {
            handlePhaseDone(pd);
        } else if (event instanceof ItemUrl iu) {
            handleItemUrl(iu);
        } else if (event instanceof ItemModIds im) {
            handleItemModIds(im);
        }
        // Shutdown is drained in stop()
    }

    private static void handlePhaseStart(PhaseStart event) {
        currentPhase = event.phase();
        totalItems = event.totalItems();
        itemIndex = 0;
        phaseStartMs = System.currentTimeMillis();
        doneCount = 0;
        failCount = 0;
        manualCount = 0;

        if (event.phase() == Phase.DOWNLOAD) {
            platform.log("--- Download phase: " + event.totalItems() + " files, "
                    + formatBytes(event.totalBytes()) + " total ---");
        } else {
            platform.log("--- Install phase: " + event.totalItems() + " items ---");
        }

        updateSwingPhase();
    }

    private static void handleItemStart(ItemStart event) {
        itemIndex++;
        currentItemName = event.name();
        currentItemTotalBytes = event.totalBytes();
        currentExtra = event.extra() != null ? event.extra() : "";
        lastProgressLogMs = 0;
        lastSpeedSampleBytes = 0;
        lastSpeedSampleMs = 0;
        currentSpeedText = "";
        currentModIds = "";
        currentUrl = "";

        String verb = currentPhase == Phase.DOWNLOAD ? "Downloading" : "Installing";
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(" [").append(itemIndex).append('/')
                .append(totalItems).append("] ").append(event.name());

        if (currentPhase == Phase.DOWNLOAD && event.totalBytes() > 0) {
            sb.append(" (").append(formatBytes(event.totalBytes())).append(')');
        }
        if (event.extra() != null && !event.extra().isEmpty()) {
            if (currentPhase == Phase.DOWNLOAD) {
                sb.append(" from ");
            } else {
                sb.append(' ');
            }
            sb.append('(').append(event.extra()).append(')');
        }

        platform.log(sb.toString());

        updateSwingItemStart();
    }

    private static void handleProgressTick(ProgressTick event) {
        if (currentItemTotalBytes <= 0) {
            return;
        }

        // Speed calculation (sample every ~1s)
        long now = System.currentTimeMillis();
        if (lastSpeedSampleMs > 0 && now - lastSpeedSampleMs >= 1000) {
            long deltaBytes = event.bytesRead() - lastSpeedSampleBytes;
            long deltaMs = now - lastSpeedSampleMs;
            if (deltaMs > 0 && deltaBytes >= 0) {
                double bytesPerSec = deltaBytes * 1000.0 / deltaMs;
                currentSpeedText = formatSpeed(bytesPerSec);
            }
            lastSpeedSampleBytes = event.bytesRead();
            lastSpeedSampleMs = now;
        } else if (lastSpeedSampleMs == 0) {
            lastSpeedSampleBytes = event.bytesRead();
            lastSpeedSampleMs = now;
        }

        // Log throttling
        if (lastProgressLogMs > 0 && now - lastProgressLogMs < PROGRESS_LOG_INTERVAL_MS) {
            updateSwingProgress(event.bytesRead());
            return;
        }
        lastProgressLogMs = now;

        int pct = (int) (event.bytesRead() * 100 / currentItemTotalBytes);
        platform.log("  " + currentItemName + "  " + pct + "%  "
                + formatBytes(event.bytesRead()) + " / " + formatBytes(currentItemTotalBytes));

        updateSwingProgress(event.bytesRead());
    }

    private static void handleItemDone(ItemDone event) {
        if (event.success()) {
            doneCount++;
        } else {
            failCount++;
        }

        String prefix = event.success() ? "  OK " : "  FAIL ";
        if (event.success()) {
            platform.log(prefix + currentItemName + " " + event.message());
        } else {
            platform.log(prefix + currentItemName + ": " + event.message());
        }

        updateSwingItemDone();
    }

    private static void handlePhaseDone(PhaseDone event) {
        // Use event counts as authoritative
        doneCount = event.ok();
        failCount = event.failed();
        manualCount = event.manual();

        StringBuilder sb = new StringBuilder();
        sb.append("--- ");
        if (currentPhase == Phase.DOWNLOAD) {
            sb.append("Download summary: ");
        } else {
            sb.append("Install summary: ");
        }
        sb.append(event.ok()).append(" ok");
        if (event.failed() > 0) {
            sb.append(", ").append(event.failed()).append(" failed");
        }
        if (event.manual() > 0) {
            sb.append(", ").append(event.manual()).append(" manual");
        }
        sb.append(" (took ").append(formatDuration(event.durationMs())).append(')');
        sb.append(" ---");

        platform.log(sb.toString());

        updateSwingPhaseDone();
    }

    private static void handleItemUrl(ItemUrl event) {
        currentUrl = event.url();
        updateSwingUrl();
    }

    private static void handleItemModIds(ItemModIds event) {
        currentModIds = String.join(", ", event.modIds());
        updateSwingMods();
    }

    // ---- Swing window management --------------------------------------

    private static void createSwingWindow() {
        swingDialog = new JDialog();
        swingDialog.setTitle("MC Client Update");
        swingDialog.setUndecorated(true);
        swingDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        swingDialog.setAlwaysOnTop(true);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        // Title
        swingTitleLabel = new JLabel("MC Client Update");
        swingTitleLabel.setFont(swingTitleLabel.getFont().deriveFont(14f).deriveFont(java.awt.Font.BOLD));
        swingTitleLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        root.add(swingTitleLabel);
        root.add(Box.createVerticalStrut(8));

        // Progress bar
        swingProgressBar = new JProgressBar(0, 100);
        swingProgressBar.setStringPainted(true);
        swingProgressBar.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        swingProgressBar.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 26));
        root.add(swingProgressBar);
        root.add(Box.createVerticalStrut(8));

        // File name label
        swingFileLabel = plainLabel("");
        root.add(swingFileLabel);
        root.add(Box.createVerticalStrut(2));

        // Size / speed label
        swingSizeSpeedLabel = plainLabel("");
        root.add(swingSizeSpeedLabel);
        root.add(Box.createVerticalStrut(6));

        // Mods label
        swingModsLabel = plainLabel("");
        root.add(swingModsLabel);
        root.add(Box.createVerticalStrut(2));

        // Source label
        swingSourceLabel = plainLabel("");
        root.add(swingSourceLabel);
        root.add(Box.createVerticalStrut(2));

        // URL label
        swingUrlLabel = plainLabel("");
        root.add(swingUrlLabel);
        root.add(Box.createVerticalStrut(6));

        // Total progress label
        swingTotalLabel = plainLabel("");
        root.add(swingTotalLabel);

        swingDialog.setContentPane(root);
        swingDialog.pack();
        swingDialog.setSize(520, swingDialog.getHeight());
        swingDialog.setLocationRelativeTo(null);
        swingDialog.setVisible(true);
    }

    private static JLabel plainLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(12f));
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void disposeSwingWindow() {
        if (!swingAvailable || swingDialog == null) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (swingDialog.isDisplayable()) {
                    swingDialog.dispose();
                }
            });
        } catch (Exception ignored) {
        }
        swingAvailable = false;
    }

    // ---- Swing UI update helpers (called from display thread) ---------

    private static final long SWING_UPDATE_INTERVAL_MS = 100;
    private static long lastSwingUpdateMs;

    private static void updateSwing(Runnable action) {
        if (!swingAvailable) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSwingUpdateMs < SWING_UPDATE_INTERVAL_MS) {
            return;
        }
        lastSwingUpdateMs = now;
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    if (swingDialog == null || !swingDialog.isDisplayable()) {
                        swingAvailable = false;
                        return;
                    }
                    action.run();
                } catch (Exception e) {
                    swingAvailable = false;
                    platform.log("Swing update failed, log-only mode: " + e);
                }
            });
        } catch (Exception e) {
            swingAvailable = false;
        }
    }

    private static void updateSwingPhase() {
        updateSwing(() -> {
            String title;
            if (currentPhase == Phase.DOWNLOAD) {
                title = "MC Client Update - Downloading...";
            } else {
                title = "MC Client Update - Installing...";
            }
            swingTitleLabel.setText(title);
            swingProgressBar.setValue(0);
            swingProgressBar.setString("0%");
            swingFileLabel.setText("");
            swingSizeSpeedLabel.setText("");
            swingModsLabel.setText("");
            swingSourceLabel.setText("");
            swingUrlLabel.setText("");
            updateSwingTotalLabel();
        });
    }

    private static void updateSwingItemStart() {
        updateSwing(() -> {
            swingFileLabel.setText(currentItemName);
            swingSizeSpeedLabel.setText("");
            swingModsLabel.setText(modsDisplayText());
            if (currentPhase == Phase.DOWNLOAD) {
                swingSourceLabel.setText("From: " + currentExtra);
                swingUrlLabel.setText("");
                swingSizeSpeedLabel.setVisible(true);
                swingSourceLabel.setVisible(true);
                swingUrlLabel.setVisible(true);
            } else {
                swingSourceLabel.setText("Action: " + currentExtra);
                swingSizeSpeedLabel.setVisible(false);
                swingSourceLabel.setVisible(true);
                swingUrlLabel.setVisible(false);
            }
            swingProgressBar.setValue(0);
            swingProgressBar.setString("0%");
            updateSwingTotalLabel();
        });
    }

    private static void updateSwingProgress(long bytesRead) {
        updateSwing(() -> {
            if (currentPhase != Phase.DOWNLOAD || currentItemTotalBytes <= 0) {
                return;
            }
            int pct = (int) (bytesRead * 100 / currentItemTotalBytes);
            swingProgressBar.setValue(pct);
            swingProgressBar.setString(pct + "%");
            swingSizeSpeedLabel.setText(formatBytes(bytesRead) + " / "
                    + formatBytes(currentItemTotalBytes)
                    + (currentSpeedText.isEmpty() ? "" : "    " + currentSpeedText));
        });
    }

    private static void updateSwingItemDone() {
        updateSwing(() -> {
            if (currentPhase == Phase.INSTALL) {
                swingProgressBar.setValue(itemIndex * 100 / totalItems);
                swingProgressBar.setString((itemIndex * 100 / totalItems) + "%");
            } else {
                swingProgressBar.setValue(100);
                swingProgressBar.setString("100%");
            }
            updateSwingTotalLabel();
        });
    }

    private static void updateSwingPhaseDone() {
        updateSwing(() -> {
            swingProgressBar.setValue(100);
            swingProgressBar.setString("100%");
            updateSwingTotalLabel();
        });
    }

    private static void updateSwingUrl() {
        updateSwing(() -> {
            String display;
            if (currentUrl.length() > 55) {
                display = currentUrl.substring(0, 55) + "...";
            } else {
                display = currentUrl;
            }
            swingUrlLabel.setText("URL: " + display);
            swingUrlLabel.setToolTipText(currentUrl);
        });
    }

    private static void updateSwingMods() {
        updateSwing(() -> {
            swingModsLabel.setText("Mods: " + modsDisplayText());
        });
    }

    private static String modsDisplayText() {
        if (currentModIds.isEmpty()) {
            return "";
        }
        return currentModIds;
    }

    private static void updateSwingTotalLabel() {
        // This runs inside updateSwing's invokeLater, so we directly set text
        StringBuilder sb = new StringBuilder();
        if (currentPhase == Phase.DOWNLOAD) {
            sb.append("Files: ");
        } else {
            sb.append("Items: ");
        }
        sb.append(itemIndex).append(" / ").append(totalItems);
        sb.append("    Done: ").append(doneCount);
        sb.append("  Failed: ").append(failCount);
        if (currentPhase == Phase.DOWNLOAD) {
            sb.append("  Manual: ").append(manualCount);
        }
        swingTotalLabel.setText(sb.toString());
    }

    // ---- Formatting helpers -------------------------------------------

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024.0) {
            return String.format("%.0f B/s", bytesPerSec);
        }
        double kbPerSec = bytesPerSec / 1024.0;
        if (kbPerSec < 1024.0) {
            return String.format("%.1f KB/s", kbPerSec);
        }
        return String.format("%.1f MB/s", kbPerSec / 1024.0);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return minutes + "m" + seconds + "s";
    }

    // ---- Reserved for future Swing implementation ---------------------

    /*
     * Placeholder for Swing progress window.
     *
     * Intended API:
     *   - On PhaseStart: create or show JDialog with JProgressBar + JTextArea
     *   - On ItemStart: append item to text area, update status label
     *   - On ProgressTick: update JProgressBar value
     *   - On ItemDone: mark item complete in text area
     *   - On PhaseDone: update summary
     *   - On Shutdown: dispose dialog
     *
     * Headless check: GraphicsEnvironment.isHeadless()
     * All Swing mutations via SwingUtilities.invokeLater()
     */
}
