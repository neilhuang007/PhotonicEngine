$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    # Exercise the real property-to-GLSL contract, material packing, negative
    # light coordinates, and shader integration. In-game validation remains
    # necessary; these checks cannot certify rendered image quality.
    & ./gradlew :modules:core:test --console=plain `
        --tests '*EmissiveBlockShaderRegressionTest' `
        --tests '*TransparencyDefinesTest' `
        --tests '*TextureDataTest' `
        --tests '*TracedLightPositionTest'
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
