package at.redi2go.photonics.impl.mc.blaze3d.opengl;

import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/** GL buffer DSA compatibility shim. Picks the best available entry-point family once at static init. */
public final class GlDsaCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlDsaCompat.class);

    private enum Mode { GL45_CORE, ARB_DSA, LEGACY_BIND }

    private static final Mode mode;
    private static final GLCapabilities caps;

    // Guards the one-time warn when immutable-storage is not available in legacy mode.
    private static volatile boolean storageFallbackWarned = false;

    static {
        caps = GL.getCapabilities();
        if (caps.OpenGL45) {
            mode = Mode.GL45_CORE;
            LOGGER.info("GlDsaCompat: using GL 4.5 core DSA entry points");
        } else if (caps.GL_ARB_direct_state_access) {
            mode = Mode.ARB_DSA;
            LOGGER.info("GlDsaCompat: using ARB_direct_state_access extension fallback");
        } else {
            mode = Mode.LEGACY_BIND;
            LOGGER.info("GlDsaCompat: using legacy bind-and-modify fallback (no DSA available)");
        }
    }

    private GlDsaCompat() {}

    // GL_COPY_WRITE_BUFFER is deliberately used for single-buffer ops to avoid disturbing
    // the texture/VBO/IBO bind points that Iris and Minecraft use during the same frame.
    private static void bindWrite(int buffer) {
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
    }

    private static void unbindWrite() {
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
    }

    public static int createBuffer() {
        return switch (mode) {
            case GL45_CORE -> GL45C.glCreateBuffers();
            case ARB_DSA -> ARBDirectStateAccess.glCreateBuffers();
            case LEGACY_BIND -> GL15C.glGenBuffers();
        };
    }

    public static void namedBufferStorage(int buffer, long size, int flags) {
        switch (mode) {
            case GL45_CORE -> GL45C.glNamedBufferStorage(buffer, size, flags);
            case ARB_DSA -> ARBDirectStateAccess.glNamedBufferStorage(buffer, size, flags);
            case LEGACY_BIND -> {
                bindWrite(buffer);
                if (caps.GL_ARB_buffer_storage) {
                    ARBBufferStorage.glBufferStorage(GL31C.GL_COPY_WRITE_BUFFER, size, flags);
                } else if (caps.OpenGL44) {
                    GL44C.glBufferStorage(GL31C.GL_COPY_WRITE_BUFFER, size, flags);
                } else {
                    if (!storageFallbackWarned) {
                        storageFallbackWarned = true;
                        LOGGER.warn("GlDsaCompat: glBufferStorage unavailable; falling back to glBufferData — immutable-storage semantics are lost");
                    }
                    GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, size, GL15C.GL_DYNAMIC_DRAW);
                }
                unbindWrite();
            }
        }
    }

    public static ByteBuffer mapNamedBufferRange(int buffer, long offset, long length, int access) {
        return switch (mode) {
            case GL45_CORE -> GL45C.glMapNamedBufferRange(buffer, offset, length, access);
            case ARB_DSA -> ARBDirectStateAccess.glMapNamedBufferRange(buffer, offset, length, access);
            case LEGACY_BIND -> {
                bindWrite(buffer);
                ByteBuffer mapped = GL30C.glMapBufferRange(GL31C.GL_COPY_WRITE_BUFFER, offset, length, access);
                unbindWrite();
                yield mapped;
            }
        };
    }

    public static boolean unmapNamedBuffer(int buffer) {
        return switch (mode) {
            case GL45_CORE -> GL45C.glUnmapNamedBuffer(buffer);
            case ARB_DSA -> ARBDirectStateAccess.glUnmapNamedBuffer(buffer);
            case LEGACY_BIND -> {
                bindWrite(buffer);
                boolean result = GL15C.glUnmapBuffer(GL31C.GL_COPY_WRITE_BUFFER);
                unbindWrite();
                yield result;
            }
        };
    }

    public static void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        switch (mode) {
            case GL45_CORE -> GL45C.glNamedBufferSubData(buffer, offset, data);
            case ARB_DSA -> ARBDirectStateAccess.glNamedBufferSubData(buffer, offset, data);
            case LEGACY_BIND -> {
                bindWrite(buffer);
                GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, offset, data);
                unbindWrite();
            }
        }
    }

    public static void copyNamedBufferSubData(int readBuffer, int writeBuffer, long readOffset, long writeOffset, long size) {
        switch (mode) {
            case GL45_CORE -> GL45C.glCopyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset, size);
            case ARB_DSA -> ARBDirectStateAccess.glCopyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset, size);
            case LEGACY_BIND -> {
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, readBuffer);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, writeBuffer);
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, readOffset, writeOffset, size);
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
                unbindWrite();
            }
        }
    }
}
