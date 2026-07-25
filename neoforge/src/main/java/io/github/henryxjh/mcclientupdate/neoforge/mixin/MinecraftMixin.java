package io.github.henryxjh.mcclientupdate.neoforge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.henryxjh.mcclientupdate.ui.DisplaySnapshot;
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    private static final float R = 239f / 255f;
    private static final float G = 50f / 255f;
    private static final float B = 61f / 255f;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
            shift = At.Shift.AFTER))
    private void onPostGameRender(boolean tick, CallbackInfo ci) {
        DisplaySnapshot snap = UpdateProgressDisplay.getSnapshot();
        if (snap == null) return;

        @SuppressWarnings("resource")
        Minecraft mc = (Minecraft) (Object) this;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        RenderSystem.backupProjectionMatrix();
        Matrix4f ortho = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();

        // Full-screen red background
        fillRect(0, 0, w, h, R, G, B, 1f);

        // Re-enable blend for text rendering (fillRect disables it)
        RenderSystem.enableBlend();

        // --- simple overlay text ---
        Font font = mc.font;
        MultiBufferSource.BufferSource bufSource = mc.renderBuffers().bufferSource();
        String title = "\u00a76mc-client-update";
        String subtitle = snap.phase() == DisplaySnapshot.Phase.DOWNLOAD
                ? "\u00a7fDownloading..." : "\u00a7fInstalling...";
        int cx = w / 2;
        int y = h / 3;
        drawCentered(font, bufSource, title, cx, y, 0xFFFFAA00);
        drawCentered(font, bufSource, subtitle, cx, y + 16, WHITE);

        int done = snap.doneCount() + snap.failCount();
        int total = snap.totalItems();
        if (total > 0) {
            drawCentered(font, bufSource, done + "/" + total + " mods", cx, y + 36, WHITE);
        }

        String cur = !snap.currentItemName().isEmpty() ? snap.currentItemName() : "";
        if (!cur.isEmpty()) {
            drawCentered(font, bufSource, "\u00a77" + cur, cx, y + 52, GRAY);
        }

        if (snap.phase() == DisplaySnapshot.Phase.COMPLETE) {
            drawCentered(font, bufSource,
                    "\u00a7aInstalled: " + snap.installedSummary().size()
                    + "  \u00a76Manual: " + snap.manualSummary().size()
                    + "  \u00a7cFailed: " + snap.failedSummary().size(),
                    cx, y + 68, WHITE);
        }

        bufSource.endBatch();

        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private static void fillRect(int x, int y, int x2, int y2, float r, float g, float b, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f pose = new Matrix4f();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(pose, x, y2, 0).setColor(r, g, b, a);
        builder.addVertex(pose, x2, y2, 0).setColor(r, g, b, a);
        builder.addVertex(pose, x2, y, 0).setColor(r, g, b, a);
        builder.addVertex(pose, x, y, 0).setColor(r, g, b, a);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private static void drawCentered(Font font, MultiBufferSource bufSource, String text, int cx, int y, int color) {
        int textW = font.width(text);
        font.drawInBatch(text, cx - textW / 2f, y, color, true,
                new Matrix4f(), bufSource, Font.DisplayMode.NORMAL, 15728880, 0);
    }
}

