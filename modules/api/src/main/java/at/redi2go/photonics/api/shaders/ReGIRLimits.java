package at.redi2go.photonics.api.shaders;

/**
 * ReGIR scalar and resource limits shared by shader-pack parsing and runtime
 * layout validation.
 */
public final class ReGIRLimits {
    public static final int RIS_RECORD_BYTES = 2 * Integer.BYTES;

    /**
     * OpenGL 4.3 guarantees at least 128 MiB for
     * {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}.
     */
    public static final long PORTABLE_SHADER_STORAGE_BLOCK_BYTES =
            128L * 1024L * 1024L;

    public static final int SUPPORTED_GRID_AXIS_MIN = 1;
    public static final int SUPPORTED_GRID_AXIS_MAX = 256;
    public static final int SUPPORTED_LIGHTS_PER_CELL_MIN = 1;
    public static final int SUPPORTED_LIGHTS_PER_CELL_MAX = 8192;

    /**
     * Shader-pack controls are independent, so their complete Cartesian
     * product must be safe. Keeping the RTXDI default of 512 lights per cell
     * makes 32 the largest symmetric power-of-two grid axis that fits the
     * portable limit:
     * {@code 32 * 32 * 32 * 512 * RIS_RECORD_BYTES = 128 MiB}.
     */
    public static final int PORTABLE_UI_GRID_AXIS_MAX = 32;
    public static final int PORTABLE_UI_LIGHTS_PER_CELL_MAX = 512;

    private ReGIRLimits() {
    }
}
