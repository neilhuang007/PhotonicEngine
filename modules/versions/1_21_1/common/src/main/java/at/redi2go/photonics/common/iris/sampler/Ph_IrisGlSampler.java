package at.redi2go.photonics.common.iris.sampler;

import net.irisshaders.iris.gl.sampler.GlSampler;

// Iris 1.8.8 GlSampler has no int-constructor, so we subclass and override
// getId(). During GlSampler's constructor, substituteId is still unset, so
// getId() must fall back to the junk sampler allocated by GlSampler itself.
// That junk sampler is freed by Iris's own destroy() path; our substitute id is
// owned by the underlying Ph_GlGpuSampler and freed by its own close().
public final class Ph_IrisGlSampler extends GlSampler {
    private final int substituteId;

    public Ph_IrisGlSampler(int substituteId) {
        super(false, false, false, false);
        this.substituteId = substituteId;
    }

    @Override
    public int getId() {
        return substituteId == 0 ? super.getId() : substituteId;
    }
}
