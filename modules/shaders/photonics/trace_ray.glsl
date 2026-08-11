#ifndef PH_TRACE_RAY_INCLUDE
#define PH_TRACE_RAY_INCLUDE

#include "/photonics/tracing.glsl"

bool trace_ray(
    inout RayIterator ray,
    out RayResult result,
    out vec3 tint_color
) {
    vec4 tint_accumulator = vec4(0.0f);
    result = missed_ray_result();
    tint_color = vec3(1.0f);

    while (ray_iter_has_next(ray)) {
        result = ray_iter_next(ray);

        if (!ray_result_is_transparent(result)) {
            tint_color = tint_accumulator.a == 0.0f
                    ? vec3(1.0f)
                    : clamp(tint_accumulator.rgb, 0.0f, 1.0f);
            return true;
        }

        VoxelData voxel_data = ray_result_voxel_data(result);
        vec4 albedo = voxel_data_albedo(voxel_data);
        ray_iter_accumulate_transparency_tint(
            ray,
            tint_accumulator,
            voxel_data,
            albedo
        );

        ray_iter_skip_transparent(ray);
        ray_iter_offset_position(ray, ray.direction * 0.03f);
    }

    tint_color = tint_accumulator.a == 0.0f
            ? vec3(1.0f)
            : clamp(tint_accumulator.rgb, 0.0f, 1.0f);
    return false;
}

// Legacy shaderpack bridge. New Photonics shaders use RayIterator directly.
struct RayJob {
    vec3 origin;
    vec3 direction;
    vec3 result_position;
    vec3 result_normal;
    vec3 result_color;
    bool result_hit;
};

int RAY_ITERATION_COUNT = PH_RAY_DEFAULT_ITERATIONS;
vec3 result_tint_color = vec3(1.0f);
int ph_legacy_result_sky_light = 0;

void ph_trace_legacy_ray(inout RayJob job, bool trace_transparency) {
    RayIterator ray;
    ray_iter_begin(ray, job.origin, job.direction);
    ray.iterations = min(ray.iterations, max(RAY_ITERATION_COUNT, 0));

    RayResult result = missed_ray_result();
    if (trace_transparency) {
        job.result_hit = trace_ray(ray, result, result_tint_color);
    } else {
        result = ray_iter_next(ray);
        job.result_hit = ray_result_is_hit(result);
        result_tint_color = vec3(1.0f);
    }

    if (!job.result_hit) {
        job.result_position = vec3(0.0f);
        job.result_normal = vec3(0.0f);
        job.result_color = vec3(0.0f);
        ph_legacy_result_sky_light = 0;
        return;
    }

    job.result_position = ray_result_position(result);
    job.result_normal = ray_result_normal(result);
    job.result_color = voxel_data_albedo(
        ray_result_voxel_data(result)
    ).rgb;
    ph_legacy_result_sky_light = int(ray_result_skylight(result));
}

void trace_ray(inout RayJob job, bool trace_transparency) {
    ph_trace_legacy_ray(job, trace_transparency);
}

void trace_ray(inout RayJob job) {
    ph_trace_legacy_ray(job, false);
}

int get_result_sky_light(vec3 normal) {
    return ph_legacy_result_sky_light;
}

#endif
