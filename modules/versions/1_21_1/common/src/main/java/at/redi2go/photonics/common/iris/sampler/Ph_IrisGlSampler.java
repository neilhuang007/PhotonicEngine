package at.redi2go.photonics.common.iris.sampler;

import net.irisshaders.iris.gl.sampler.GlSampler;

// Iris 1.8.8 GlSampler has no int-constructor, so we subclass and override
// getId(). The super constructor allocates a junk GL sampler that is freed by
// Iris's own destroy() path (destroyInternal calls IrisRenderSystem.destroySampler
// on getGlId(), which stays bound to the original id). Our substitute id is
// owned by the underlying Ph_GlGpuSampler and freed by its own close().
public final class Ph_IrisGlSampler extends GlSampler {
    private final int substituteId;

    public Ph_IrisGlSampler(int substituteId) {
        super(false, false, false, false);
        this.substituteId = substituteId;
    }

    @Override
    public int getId() {
        return substituteId;
    }
}
