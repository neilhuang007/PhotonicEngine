#include "/photonics/uniforms.glsl"
#include "/photonics/interface/world_interface.glsl"
#include "/photonics/utility/random.glsl"
#include "/photonics/utility/normal_encoding.glsl"

uniform int frameCounter;
uniform float viewWidth;
uniform float viewHeight;

uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;

uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;

vec2 get_taa_jitter() {
#ifdef TAA
    vec2 view = 1.0 / vec2(viewWidth, viewHeight);

    #if TAA_MODE == 0
    vec2 jitterOffsets8[8] = vec2[8](
        vec2( 0.125, -0.375),
        vec2(-0.125,  0.375),
        vec2( 0.625,  0.125),
        vec2( 0.375, -0.625),
        vec2(-0.625,  0.625),
        vec2(-0.875, -0.125),
        vec2( 0.375, -0.875),
        vec2( 0.875,  0.875)
    );
    return jitterOffsets8[int(frameCounter & 7)] * view;
    #else
    vec2 jitterOffsets2[2] = vec2[2](
        vec2(1.0, 0.0),
        vec2(0.0, 1.0)
    );
    return jitterOffsets2[int(frameCounter & 1)] * view;
    #endif
#endif

    return vec2(0.0);
}

ivec2 frag_tex_coord = ivec2(0);
uint frag_rnd_state = 0;

vec3 frag_rt_pos = vec3(0.0f);
vec3 frag_player_pos = vec3(0.0f);

vec3 frag_geo_normal = vec3(0.0f);
vec3 frag_tex_normal = vec3(0.0f);
bool frag_is_bad_angle = false;
bool frag_is_hand = false;

#define PH_VIEW_SIZE (vec2(viewWidth, viewHeight) * PH_RENDER_SCALE)

bool is_bad_angle(vec3 rt_pos, vec3 normal) {
    float dist = distance(floor(rt_pos), floor(rt_camera_position));

    float resolution = ceil((dist / 16)) / (4 * PH_RENDER_SCALE);
    return dot(normal, normalize(rt_pos - rt_camera_position)) > -0.2f && dist > 16.0f;
}

bool prepare_frag(int rnd_seed) {
    frag_tex_coord = ivec2(gl_FragCoord.xy);
    if (!is_in_world_at(frag_tex_coord)) return false;

    frag_rnd_state = new_rand_state(gl_FragCoord.xy, frameCounter, 0);

    frag_player_pos = get_player_position(gl_FragCoord.xy);
    frag_rt_pos = frag_player_pos + rt_camera_position;
    get_fragment_data(gl_FragCoord.xy, frag_geo_normal, frag_tex_normal);

    frag_is_hand = is_hand_at(gl_FragCoord.xy);

    // Attempts to correct bias from depth

    vec3 voxel_pos = frag_rt_pos * 16.0f;
    uint normal_index = ph_encode_voxel_normal(frag_geo_normal);
    float normal_pos = round(voxel_pos[normal_index >> 1]);

    vec3 view_dir =  normalize(frag_rt_pos - rt_camera_position);
    float view_distance = distance(frag_rt_pos, rt_camera_position);

    voxel_pos += (view_dir * (view_distance * 0.03));
    voxel_pos[normal_index >> 1] = normal_pos;

    const float rcp_16 = 1.0f / 16.0f;
    frag_rt_pos = voxel_pos * rcp_16;
    frag_is_bad_angle = is_bad_angle(frag_rt_pos, frag_geo_normal);

    return true;
}
