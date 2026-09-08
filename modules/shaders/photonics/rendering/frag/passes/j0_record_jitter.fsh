#version 430
#include "/photonics/rendering/frag/world_interface.glsl"
layout(location = 0) out vec2 jitter_out;
void main() {
    jitter_out = get_taa_jitter();
}
