#ifndef PH_REGIR_POWER_PDF_SAMPLING_INCLUDE
#define PH_REGIR_POWER_PDF_SAMPLING_INCLUDE

#include "/photonics/rendering/restir/power_ris/power_pdf.glsl"
#include "/photonics/rendering/restir/power_ris/random.glsl"

bool ph_regir_power_pdf_sample(
        inout PhPowerRisRandom random,
        out uvec2 position,
        out float pdf
) {
    int last_mip_level = max(0, int(ph_regir_power_pdf_mip_levels()) - 2);

    position = uvec2(0u);
    pdf = 1.0f;

    for (int mip_level = last_mip_level; mip_level >= 0; mip_level--) {
        position *= 2u;

        vec4 weights = max(
                vec4(
                        ph_regir_power_pdf_load(ivec2(position) + ivec2(0, 0), uint(mip_level)),
                        ph_regir_power_pdf_load(ivec2(position) + ivec2(0, 1), uint(mip_level)),
                        ph_regir_power_pdf_load(ivec2(position) + ivec2(1, 0), uint(mip_level)),
                        ph_regir_power_pdf_load(ivec2(position) + ivec2(1, 1), uint(mip_level))
                ),
                vec4(0.0f)
        );

        float weight_sum = dot(weights, vec4(1.0f));
        if (!(weight_sum > 0.0f) || isnan(weight_sum) || isinf(weight_sum)) {
            position = uvec2(0u);
            pdf = 0.0f;
            return false;
        }

        weights /= weight_sum;
        float draw = ph_power_ris_random_next(random);

        if (draw < weights.x) {
            pdf *= weights.x;
        } else {
            draw -= weights.x;

            if (draw < weights.y) {
                position += uvec2(0u, 1u);
                pdf *= weights.y;
            } else {
                draw -= weights.y;

                if (draw < weights.z) {
                    position += uvec2(1u, 0u);
                    pdf *= weights.z;
                } else {
                    position += uvec2(1u, 1u);
                    pdf *= weights.w;
                }
            }
        }
    }

    return pdf > 0.0f && !isnan(pdf) && !isinf(pdf);
}

#endif
