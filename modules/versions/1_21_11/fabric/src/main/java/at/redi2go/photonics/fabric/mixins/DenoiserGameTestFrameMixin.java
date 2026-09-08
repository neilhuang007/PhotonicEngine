package at.redi2go.photonics.fabric.mixins;

import at.redi2go.photonics.client.DenoiserGameTestReporter;
import at.redi2go.photonics.core.iris.rendering.PhotonicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies an exact post-Photonics-render boundary to the opt-in game test. */
@Mixin(value = PhotonicsPipeline.class, remap = false)
abstract class DenoiserGameTestFrameMixin {
    @Inject(method = "onRender", at = @At("TAIL"))
    private void photonics$afterDenoiserRender(CallbackInfo ci) {
        DenoiserGameTestReporter.onPhotonicsFrame(
                (PhotonicsPipeline) (Object) this
        );
    }
}
