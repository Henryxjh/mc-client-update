package io.github.henryxjh.mcclientupdate.neoforge.mixin;

import io.github.henryxjh.mcclientupdate.ui.McUpdateOverlay;
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
            shift = At.Shift.BEFORE))
    private void onPreUpdateDisplay(boolean tick, CallbackInfo ci) {
        if (UpdateProgressDisplay.getSnapshot() != null) {
            Minecraft mc = (Minecraft) (Object) this;
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            McUpdateOverlay.renderOverlay(graphics);
            graphics.flush();
        }
    }
}
