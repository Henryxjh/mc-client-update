package io.github.henryxjh.mcclientupdate.ui;

import io.github.henryxjh.mcclientupdate.platform.PlatformContext;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated progress display thread that receives events from the download and
 * install phases and produces real-time log output.
 *
 * <p>Swing progress window support is reserved but not yet implemented.
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
            permits PhaseStart, ItemStart, ProgressTick, ItemDone, PhaseDone, Shutdown {}

    private record PhaseStart(Phase phase, int totalItems, long totalBytes) implements Event {}
    private record ItemStart(String name, long totalBytes, String extra) implements Event {}
    private record ProgressTick(long bytesRead) implements Event {}
    private record ItemDone(boolean success, String message) implements Event {}
    private record PhaseDone(int ok, int failed, int manual, long durationMs) implements Event {}
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

    // ---- Public API ---------------------------------------------------

    /**
     * Starts the progress display thread. Must be called before any report
     * methods.
     */
    public static void start(PlatformContext ctx) {
        Objects.requireNonNull(ctx, "platform");
        platform = ctx;
        running = true;
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
        }
        // Shutdown is drained in stop()
    }

    private static void handlePhaseStart(PhaseStart event) {
        currentPhase = event.phase();
        totalItems = event.totalItems();
        itemIndex = 0;
        phaseStartMs = System.currentTimeMillis();

        if (event.phase() == Phase.DOWNLOAD) {
            platform.log("--- Download phase: " + event.totalItems() + " files, "
                    + formatBytes(event.totalBytes()) + " total ---");
        } else {
            platform.log("--- Install phase: " + event.totalItems() + " items ---");
        }
    }

    private static void handleItemStart(ItemStart event) {
        itemIndex++;
        currentItemName = event.name();
        currentItemTotalBytes = event.totalBytes();
        lastProgressLogMs = 0;

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
    }

    private static void handleProgressTick(ProgressTick event) {
        if (currentItemTotalBytes <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastProgressLogMs > 0 && now - lastProgressLogMs < PROGRESS_LOG_INTERVAL_MS) {
            return;
        }
        lastProgressLogMs = now;

        int pct = (int) (event.bytesRead() * 100 / currentItemTotalBytes);
        platform.log("  " + currentItemName + "  " + pct + "%  "
                + formatBytes(event.bytesRead()) + " / " + formatBytes(currentItemTotalBytes));
    }

    private static void handleItemDone(ItemDone event) {
        String prefix = event.success() ? "  OK " : "  FAIL ";
        if (event.success()) {
            platform.log(prefix + currentItemName + " " + event.message());
        } else {
            platform.log(prefix + currentItemName + ": " + event.message());
        }
    }

    private static void handlePhaseDone(PhaseDone event) {
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
