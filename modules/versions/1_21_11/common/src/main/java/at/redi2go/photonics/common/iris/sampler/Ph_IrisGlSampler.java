package at.redi2go.photonics.common.iris.sampler;

import net.irisshaders.iris.gl.sampler.GlSampler;

import java.util.function.IntSupplier;

public final class Ph_IrisGlSampler extends GlSampler {
    private final IntSupplier substituteIdSupplier;

    public Ph_IrisGlSampler(IntSupplier substituteIdSupplier) {
        super(0);
        this.substituteIdSupplier = substituteIdSupplier;
    }

    @Override
    public int getId() {
        if (substituteIdSupplier == null) return super.getId();
        int id = substituteIdSupplier.getAsInt();
        return id == 0 ? super.getId() : id;
    }
}
