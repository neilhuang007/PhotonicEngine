#ifndef PH_FRAME_JITTER_INCLUDE
#define PH_FRAME_JITTER_INCLUDE
uniform sampler2D prev_ph_frame_jitter;
vec2 ph_previous_frame_jitter() {
    return texelFetch(prev_ph_frame_jitter, ivec2(0), 0).xy;
}
#endif
