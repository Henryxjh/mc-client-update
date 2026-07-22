package io.github.henryxjh.mcclientupdate.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import java.util.List;

/**
 * Vanilla-style overlay rendered inside the Minecraft window during
 * mod update downloading and installation.
 *
 * <p>Rendered via Mixin injection into {@code MinecraftClient.runTick()}.
 * No AWT/Swing dependency — works on all platforms including Android.
 *
 * <p><b>Fabric (Yarn mappings) version.</b>
 */
public final class McUpdateOverlay {

    private static final int BG_COLOR = 0xFFEF323D;
    private static final int BAR_BG = 0x40000000;
    private static final int BAR_FG = 0xFFFFFFFF;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_GRAY = 0xFFAAAAAA;
    private static final int TEXT_GREEN = 0xFF55FF55;
    private static final int TEXT_RED = 0xFFFF5555;
    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 6;
    private static final int BAR_BORDER = 1;

    private McUpdateOverlay() {}

    public static void renderOverlay(DrawContext graphics) {
        DisplaySnapshot snap = UpdateProgressDisplay.getSnapshot();
        if (snap == null) return;

        int w = graphics.getScaledWindowWidth();
        int h = graphics.getScaledWindowHeight();
        TextRenderer font = MinecraftClient.getInstance().textRenderer;

        graphics.fill(0, 0, w, h, BG_COLOR);

        int centerX = w / 2;
        int topY = h / 3;

        String phaseLabel = snap.phase() == DisplaySnapshot.Phase.DOWNLOAD ? "下载中..." : "安装中...";
        graphics.drawCenteredTextWithShadow(font, "§6mc-client-update §r" + phaseLabel, centerX, topY, TEXT_WHITE);
        topY += 18;

        int completed = snap.doneCount() + snap.failCount();
        String itemsText = completed + "/" + snap.totalItems() + " mods";
        drawBar(graphics, centerX, topY, BAR_WIDTH, BAR_HEIGHT, completed, snap.totalItems());
        graphics.drawCenteredTextWithShadow(font, itemsText, centerX, topY + BAR_HEIGHT + 3, TEXT_WHITE);
        topY += BAR_HEIGHT + 16;

        if (snap.phaseTotalBytes() > 0) {
            String bytesText = formatBytes(snap.phaseDownloadedBytes()) + " / " + formatBytes(snap.phaseTotalBytes());
            drawBar(graphics, centerX, topY, BAR_WIDTH, BAR_HEIGHT, snap.phaseDownloadedBytes(), snap.phaseTotalBytes());
            graphics.drawCenteredTextWithShadow(font, bytesText, centerX, topY + BAR_HEIGHT + 3, TEXT_GRAY);
            topY += BAR_HEIGHT + 16;
        }
        topY += 6;

        if (!snap.currentItemName().isEmpty()) {
            String prefix = snap.phase() == DisplaySnapshot.Phase.DOWNLOAD ? "↓ " : "→ ";
            graphics.drawTextWithShadow(font, prefix + snap.currentItemName(), centerX - BAR_WIDTH / 2, topY, TEXT_WHITE);
            topY += 12;

            if (snap.phase() == DisplaySnapshot.Phase.DOWNLOAD && snap.currentItemTotalBytes() > 0) {
                String curText = formatBytes(snap.currentBytesRead()) + " / " + formatBytes(snap.currentItemTotalBytes());
                if (!snap.speedText().isEmpty()) curText += "  " + snap.speedText();
                drawBar(graphics, centerX, topY, BAR_WIDTH, BAR_HEIGHT, snap.currentBytesRead(), snap.currentItemTotalBytes());
                graphics.drawTextWithShadow(font, curText, centerX - BAR_WIDTH / 2, topY + BAR_HEIGHT + 3, TEXT_GRAY);
                topY += BAR_HEIGHT + 16;
            }
            if (!snap.currentExtra().isEmpty()) {
                graphics.drawTextWithShadow(font, "from: " + snap.currentExtra(), centerX - BAR_WIDTH / 2, topY, TEXT_GRAY);
                topY += 12;
            }
            if (!snap.currentModIds().isEmpty()) {
                graphics.drawTextWithShadow(font, "mods: " + snap.currentModIds(), centerX - BAR_WIDTH / 2, topY, TEXT_GRAY);
                topY += 12;
            }
            topY += 8;
        }

        List<DisplaySnapshot.ItemResult> results = snap.recentResults();
        if (!results.isEmpty()) {
            int maxShow = Math.min(results.size(), 5);
            for (int i = results.size() - maxShow; i < results.size(); i++) {
                DisplaySnapshot.ItemResult r = results.get(i);
                String icon = r.success() ? "§a✓" : "§c✗";
                graphics.drawTextWithShadow(font, icon + " §r" + r.name() + "  " + r.message(),
                        centerX - BAR_WIDTH / 2, topY, r.success() ? TEXT_GREEN : TEXT_RED);
                topY += 11;
            }
        }
    }

    private static void drawBar(DrawContext g, int centerX, int y, int width, int height, long cur, long total) {
        int x = centerX - width / 2;
        g.fill(x - BAR_BORDER, y - BAR_BORDER, x + width + BAR_BORDER, y + height + BAR_BORDER, BAR_BG);
        if (total > 0) {
            int fillW = (int) Math.min(width, cur * width / total);
            if (fillW > 0) g.fill(x, y, x + fillW, y + height, BAR_FG);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
