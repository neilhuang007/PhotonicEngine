# CPU texture sampling — 2026-09-08

The voxel bakery's CPU sampler selected incorrect texels for valid normalized
subtexel coordinates. Its result also depended on the sprite's atlas position.
This is a confirmed sampling defect; it is not evidence that the native glass
projection's remaining blur has this cause.

## Contract and implementation

The bakery barycentrically interpolates the mesh's normalized atlas UVs and
passes them directly through `AtlasTexture.sample` to `Rgba8Texture.sample`.
The CPU texture is downloaded at mip level zero. The applicable nearest,
clamp-to-edge contract is `clamp(floor(coordinate * size), 0, size - 1)`.
OpenGL 4.6 defines normalized-to-texel coordinates in equation 8.10, integer
selection in section 8.14.2, and edge clamping in table 8.20.
See the [Khronos specification](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf),
printed pages 257, 259–260.

`Rgba8Texture` instead rounded `coordinate * (size - 0.5)`. At width 16,
u=0.1 selects texel 2 instead of texel 1. Exact texel-center inputs can still
look correct, so a centers-only regression would miss the defect. The old
`org.joml.Math.clamp` call also used the wrong parameter order: JOML 1.10.8
accepts `(minimum, maximum, value)`, whereas the call supplied
`(value, minimum, maximum)`. Positive coordinates beyond the texture edge
were therefore not bounded correctly.

The fix uses `Math.floor` and standard Java `Math.min`/`Math.max`, removes the
unused imports, and changes the existing missing-data fallback to include
`index == color.length`. A two-texel texture backed by one pixel reproduced an
`ArrayIndexOutOfBoundsException` at the first absent texel before that change.
The existing fallback value and color-channel conversion are preserved.

## Regression evidence

The exact command was run before and after the production change:

```powershell
.\gradlew.bat :modules:core:test --tests '*Rgba8TextureTest' --tests '*BlockBakeryTextureSamplingTest' --console=plain
```

Before: 10 tests executed, 8 failed. After: 10 executed, none failed or skipped.
Logs and successful XML reports are in `tmp/sampler-cpu-checkpoint/`.

The sampler tests cover subtexel bins, exact bin edges and their adjacent
floating-point coordinates, clamping outside the image, sprite-offset
independence, downloaded ABGR-to-ARGB conversion including alpha, and the
missing-data boundary.

The integration test calls the real public `MeshResultImpl.bake` seam with a
synthetic 16×16 constant-RGB texture whose detail is entirely in alpha. It
embeds that texture at four offsets in a 64×64 atlas and checks every baked
voxel across four UV rotations and two subtexel shifts. No native Minecraft
texture is redistributed and no Minecraft objects or downloader mocks are
required. Before the fix, a 0.2-texel UV shift erased an alpha border at atlas
offsets (0,0) and (16,0), and sampled the neighboring sprite at (0,32).
All variants now preserve the expected per-coordinate color, alpha, block ID,
and transmission metadata.

This integration seam starts with an already downloaded synthetic atlas. It
does not validate the GPU download, native Minecraft quad generation, palette
upload, emitter integration, or final denoised appearance. In particular,
native centered 16×16 cube textures can avoid the erroneous subtexel bins;
the actual glass scene also averages transmission over emitter endpoints.
