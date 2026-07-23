package io.github.henryxjh.mcclientupdate.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import java.util.List;

public final class McUpdateOverlay {

    private static final int BG_COLOR = 0xFFEF323D;
    private static final int BAR_BG = 0x40000000;
    private static final int BAR_FG = 0xFFFFFFFF;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_GRAY = 0xFFAAAAAA;
    private static final int TEXT_GREEN = 0xFF55FF55;
    private static final int TEXT_RED = 0xFFFF5555;
    private static final int TEXT_GOLD = 0xFFFFAA00;
    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 6;
    private static final int BAR_BORDER = 1;
    private static final int MAX_LINES_PER_SECTION = 4;

    private McUpdateOverlay() {}

    public static void renderOverlay(DrawContext graphics) {
        DisplaySnapshot snap = UpdateProgressDisplay.getSnapshot();
        if (snap == null) return;
        if (snap.phase() == DisplaySnapshot.Phase.COMPLETE) {
            renderComplete(graphics, snap);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();
        TextRenderer font = mc.textRenderer;

        graphics.fill(0, 0, w, h, BG_COLOR);
        int cx = w / 2, top = h / 3;

        String label = snap.phase() == DisplaySnapshot.Phase.DOWNLOAD ? "Downloading..." : "Installing...";
        graphics.drawCenteredTextWithShadow(font, "\u00a76mc-client-update \u00a7r" + label, cx, top, TEXT_WHITE);
        top += 18;

        int done = snap.doneCount() + snap.failCount();
        drawBar(graphics, cx, top, BAR_WIDTH, BAR_HEIGHT, done, snap.totalItems());
        graphics.drawCenteredTextWithShadow(font, done + "/" + snap.totalItems() + " mods", cx, top + BAR_HEIGHT + 3, TEXT_WHITE);
        top += BAR_HEIGHT + 16;

        if (snap.phaseTotalBytes() > 0) {
            String t = formatBytes(snap.phaseDownloadedBytes()) + " / " + formatBytes(snap.phaseTotalBytes());
            drawBar(graphics, cx, top, BAR_WIDTH, BAR_HEIGHT, snap.phaseDownloadedBytes(), snap.phaseTotalBytes());
            graphics.drawCenteredTextWithShadow(font, t, cx, top + BAR_HEIGHT + 3, TEXT_GRAY);
            top += BAR_HEIGHT + 16;
        }
        top += 6;

        if (!snap.currentItemName().isEmpty()) {
            String pf = snap.phase() == DisplaySnapshot.Phase.DOWNLOAD ? "\u2193 " : "\u2192 ";
            graphics.drawTextWithShadow(font, pf + snap.currentItemName(), cx - BAR_WIDTH / 2, top, TEXT_WHITE);
            top += 12;
            if (snap.phase() == DisplaySnapshot.Phase.DOWNLOAD && snap.currentItemTotalBytes() > 0) {
                String ct = formatBytes(snap.currentBytesRead()) + " / " + formatBytes(snap.currentItemTotalBytes());
                if (!snap.speedText().isEmpty()) ct += "  " + snap.speedText();
                drawBar(graphics, cx, top, BAR_WIDTH, BAR_HEIGHT, snap.currentBytesRead(), snap.currentItemTotalBytes());
                graphics.drawTextWithShadow(font, ct, cx - BAR_WIDTH / 2, top + BAR_HEIGHT + 3, TEXT_GRAY);
                top += BAR_HEIGHT + 16;
            }
            if (!snap.currentExtra().isEmpty()) {
                graphics.drawTextWithShadow(font, "from: " + snap.currentExtra(), cx - BAR_WIDTH / 2, top, TEXT_GRAY);
                top += 12;
            }
            if (!snap.currentModIds().isEmpty()) {
                graphics.drawTextWithShadow(font, "mods: " + snap.currentModIds(), cx - BAR_WIDTH / 2, top, TEXT_GRAY);
                top += 12;
            }
            top += 8;
        }

        List<DisplaySnapshot.ItemResult> res = snap.recentResults();
        if (!res.isEmpty()) {
            int n = Math.min(res.size(), 5);
            for (int i = res.size() - n; i < res.size(); i++) {
                var r = res.get(i);
                String icon = r.success() ? "\u00a7a\u2713" : "\u00a7c\u2717";
                graphics.drawTextWithShadow(font, icon + " \u00a7r" + r.name() + "  " + r.message(),
                        cx - BAR_WIDTH / 2, top, r.success() ? TEXT_GREEN : TEXT_RED);
                top += 11;
            }
        }
    }

    private static void renderComplete(DrawContext graphics, DisplaySnapshot snap) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();
        TextRenderer font = mc.textRenderer;

        graphics.fill(0, 0, w, h, BG_COLOR);
        int cx = w / 2, top = h / 3, lx = cx - 140;

        graphics.drawCenteredTextWithShadow(font, "\u00a76mc-client-update \u00a7rUpdate Complete", cx, top, TEXT_WHITE);
        top += 20;
        graphics.drawCenteredTextWithShadow(font, "\u00a7eRestart to apply updates", cx, top, TEXT_GOLD);
        top += 18;

        int ic = snap.installedSummary().size(), mc2 = snap.manualSummary().size(), fc = snap.failedSummary().size();
        String sum = "\u00a7aInstalled: " + ic + "  \u00a76Manual: " + mc2 + "  \u00a7cFailed: " + fc;
        graphics.drawCenteredTextWithShadow(font, sum, cx, top, TEXT_WHITE);
        top += 20;

        top = renderSection(graphics, font, snap.installedSummary(), "\u00a7aInstalled:", TEXT_GREEN, TEXT_WHITE, lx, top);
        top = renderSection(graphics, font, snap.manualSummary(), "\u00a76Manual:", TEXT_GOLD, TEXT_GRAY, lx, top);
        renderSection(graphics, font, snap.failedSummary(), "\u00a7cFailed:", TEXT_RED, TEXT_RED, lx, top);
    }

    private static int renderSection(DrawContext g, TextRenderer f, List<String> items,
                                      String title, int titleColor, int itemColor, int lx, int top) {
        if (items.isEmpty()) return top;
        int n = Math.min(items.size(), MAX_LINES_PER_SECTION);
        g.drawTextWithShadow(f, title, lx, top, titleColor);
        top += 13;
        for (int i = 0; i < n; i++) {
            g.drawTextWithShadow(f, "  " + trunc(items.get(i), 50), lx, top, itemColor);
            top += 11;
        }
        if (items.size() > MAX_LINES_PER_SECTION) {
            g.drawTextWithShadow(f, "  ... and " + (items.size() - MAX_LINES_PER_SECTION) + " more", lx, top, TEXT_GRAY);
            top += 11;
        }
        return top + 4;
    }

    private static void drawBar(DrawContext g, int cx, int y, int w, int h, long cur, long total) {
        int x = cx - w / 2;
        g.fill(x - BAR_BORDER, y - BAR_BORDER, x + w + BAR_BORDER, y + h + BAR_BORDER, BAR_BG);
        if (total > 0) {
            int fw = (int) Math.min(w, cur * w / total);
            if (fw > 0) g.fill(x, y, x + fw, y + h, BAR_FG);
        }
    }

    private static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        if (b < 1073741824) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    private static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
