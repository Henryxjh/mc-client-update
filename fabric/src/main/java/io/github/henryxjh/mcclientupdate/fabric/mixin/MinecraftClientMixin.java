package io.github.henryxjh.mcclientupdate.fabric.mixin;

import io.github.henryxjh.mcclientupdate.ui.McUpdateOverlay;
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;render(FJZ)V",
            shift = At.Shift.AFTER))
    private void onPostGameRender(boolean tick, CallbackInfo ci) {
        if (UpdateProgressDisplay.getSnapshot() != null) {
            MinecraftClient mc = (MinecraftClient) (Object) this;
            DrawContext graphics = new DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());
            McUpdateOverlay.renderOverlay(graphics);
            graphics.draw();
        }
    }
}
