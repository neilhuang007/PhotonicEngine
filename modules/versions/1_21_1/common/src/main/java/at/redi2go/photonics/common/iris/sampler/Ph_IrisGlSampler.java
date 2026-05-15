package at.redi2go.photonics.common.iris.sampler;

import net.irisshaders.iris.gl.sampler.GlSampler;

import java.util.function.IntSupplier;

// Iris 1.8.8 GlSampler has no int-constructor, so we subclass and override
// getId(). During GlSampler's constructor, substituteIdSupplier is still null
// (Java field init order), so getId() must fall back to the junk sampler
// allocated by GlSampler itself. Once construction completes, every getId()
// call re-evaluates the supplier so that sampler handle changes (texture reload,
// dimension switch) are reflected without re-registering the wrapper.
// That junk sampler is freed by Iris's own destroy() path; the substitute id is
// owned by the underlying Ph_GlGpuSampler and freed by its own close().
public final class Ph_IrisGlSampler extends GlSampler {
    private final IntSupplier substituteIdSupplier;

    public Ph_IrisGlSampler(IntSupplier substituteIdSupplier) {
        super(false, false, false, false);
        this.substituteIdSupplier = substituteIdSupplier;
    }

    @Override
    public int getId() {
        if (substituteIdSupplier == null) return super.getId();
        int id = substituteIdSupplier.getAsInt();
        return id == 0 ? super.getId() : id;
    }
}
