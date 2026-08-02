#version 430

#include "/photonics/rendering/restir/native_light_list.glsl"
#include "/photonics/utility/color.glsl"
#include "/photonics/rendering/restir/power_ris/power_pdf.glsl"

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

float ph_restir_effective_point_light_power(PhRestirNativeLight light) {
    float luminance =
            max(0.0f, ph_luminance(max(light.color, vec3(0.0f))));
    if (!(luminance > 0.0f) ||
            isnan(luminance) ||
            isinf(luminance)) {
        return 0.0f;
    }

    // Photonics' far-field attenuation is
    // color / (distance^2 * falloff * attenuation.y). Its isotropic flux is
    // therefore 4*pi*luminance(color)/(falloff*attenuation.y).
    float inverse_square_coefficient =
            light.falloff * light.attenuation.y;
    bool has_inverse_square_falloff =
            inverse_square_coefficient > 0.0f &&
            !isnan(inverse_square_coefficient) &&
            !isinf(inverse_square_coefficient);

    // Custom light definitions may use constant-only attenuation. Their
    // ReGIR volume target is still positive, so assigning zero Power-RIS
    // probability would violate proposal support and permanently lose energy.
    float constant_attenuation = light.attenuation.x;
    float proposal_denominator = has_inverse_square_falloff
            ? inverse_square_coefficient
            : constant_attenuation;
    if (!(proposal_denominator > 0.0f) ||
            isnan(proposal_denominator) ||
            isinf(proposal_denominator)) {
        return luminance;
    }

    float power = luminance /
            proposal_denominator *
            (4.0f * 3.141592653589793f);
    if (!(power > 0.0f) ||
            isnan(power) ||
            isinf(power)) {
        return luminance;
    }

    return max(power, 1e-20f);
}

void main() {
    // The packed pyramid is reduced with workgroup-scoped barriers. Ignore
    // accidental extra groups so there is still exactly one writer group.
    if (any(greaterThan(gl_WorkGroupID, uvec3(0u)))) {
        return;
    }

    uint thread_index = gl_LocalInvocationIndex;
    uvec2 base_dimensions = ph_regir_power_pdf_dimensions(0u);
    uint base_texel_count = base_dimensions.x * base_dimensions.y;

    for (uint texel_index = thread_index;
         texel_index < base_texel_count;
         texel_index += gl_WorkGroupSize.x) {
        uvec2 position = uvec2(
                texel_index % base_dimensions.x,
                texel_index / base_dimensions.x
        );
        uint light_index = ph_power_ris_morton_encode(position);
        float power = 0.0f;

        if (light_index < uint(light_list_size)) {
            PhRestirNativeLight light =
                    ph_restir_native_light_list_get(int(light_index));
            power = ph_restir_effective_point_light_power(light);
        }

        ph_regir_power_pdf_store(position, 0u, power);
    }

    uint mip_levels = ph_regir_power_pdf_mip_levels();
    for (uint mip_level = 1u; mip_level < mip_levels; mip_level++) {
        memoryBarrierBuffer();
        barrier();

        uvec2 dimensions = ph_regir_power_pdf_dimensions(mip_level);
        uint texel_count = dimensions.x * dimensions.y;

        for (uint texel_index = thread_index;
             texel_index < texel_count;
             texel_index += gl_WorkGroupSize.x) {
            uvec2 position = uvec2(
                    texel_index % dimensions.x,
                    texel_index / dimensions.x
            );
            ivec2 source_position = ivec2(position * 2u);
            float average = (
                    ph_regir_power_pdf_load(source_position + ivec2(0, 0), mip_level - 1u)
                    + ph_regir_power_pdf_load(source_position + ivec2(0, 1), mip_level - 1u)
                    + ph_regir_power_pdf_load(source_position + ivec2(1, 0), mip_level - 1u)
                    + ph_regir_power_pdf_load(source_position + ivec2(1, 1), mip_level - 1u)
            ) * 0.25f;

            ph_regir_power_pdf_store(position, mip_level, average);
        }
    }
}
