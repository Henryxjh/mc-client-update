package io.github.henryxjh.mcclientupdate.ui;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

public final class RenderDebugLog {
    private RenderDebugLog() {}

    private static BufferedWriter writer;
    private static int lineCount;
    private static boolean disabled;
    private static final Object LOCK = new Object();

    private static final int FLUSH_EVERY = 100;

    public static void log(String message, Consumer<String> warnLogger) {
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            if (writer == null) {
                start(warnLogger);
                if (writer == null) {
                    // start will set disabled and may have warned already
                    return;
                }
            }
            try {
                writer.write(message);
                writer.newLine();
                lineCount++;
                if (lineCount >= FLUSH_EVERY) {
                    writer.flush();
                    lineCount = 0;
                }
            } catch (IOException e) {
                disableAndWarn(warnLogger, "I/O error writing render debug log: " + e);
            }
        }
    }

    public static void flush() {
        synchronized (LOCK) {
            if (writer != null && !disabled) {
                try {
                    writer.flush();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (writer != null) {
                try {
                    writer.flush(); // ensure last data written
                } catch (IOException ignored) {}
                try {
                    writer.close();
                } catch (IOException ignored) {}
                writer = null;
                // do not change disabled
            }
        }
    }

    private static void start(Consumer<String> warnLogger) {
        // caller holds LOCK, writer == null && disabled == false
        String pathStr = System.getProperty("mcclientupdate.renderDebugLog");
        if (pathStr == null || pathStr.isBlank()) {
            disabled = true;
            return;
        }
        Path path;
        try {
            path = Path.of(pathStr).normalize();
        } catch (Exception e) {
            disableAndWarn(warnLogger, "Invalid render debug log path: " + pathStr + " -> " + e);
            return;
        }
        try {
            writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            disableAndWarn(warnLogger,
                    "Cannot open render debug log file " + path + " -> " + e);
        }
    }

    private static void disableAndWarn(Consumer<String> warnLogger, String message) {
        // caller holds LOCK
        if (disabled) {
            return; // already disabled
        }
        disabled = true;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
        if (warnLogger != null) {
            warnLogger.accept(message);
        }
    }
}
