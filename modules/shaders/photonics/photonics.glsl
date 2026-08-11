// TODO: Deprecated compatibility bridge for legacy shaderpacks.
#include "/photonics/samplers.glsl"

// Legacy packs such as Shrimple already include their own material and lighting
// libraries before they pull in Photonics. The legacy RayJob bridge only needs
// traversal and result decoding, so keep modifier hooks disabled while loading
// the tracing stack to avoid re-introducing the pack's lighting functions.
#define PH_VOXEL_COLOR_MODIFIER_DISABLED
#define PH_LIGHT_MODIFIER_DISABLED
#define PH_ATTENUATION_MODIFIER_DISABLED
#include "/photonics/trace_ray.glsl"
#undef PH_ATTENUATION_MODIFIER_DISABLED
#undef PH_LIGHT_MODIFIER_DISABLED
#undef PH_VOXEL_COLOR_MODIFIER_DISABLED
