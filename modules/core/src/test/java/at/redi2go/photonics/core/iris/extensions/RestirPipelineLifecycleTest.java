package at.redi2go.photonics.core.iris.extensions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RestirPipelineLifecycleTest {
    @Test
    void reservoirSplattingIsCreatedOnlyUnderBlockLightGate()
            throws IOException {
        String source = Files.readString(findRepositoryRoot().resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));

        int constructor = source.indexOf(
                "new ReservoirSplattingRendering("
        );
        assertTrue(constructor >= 0, "reservoir splatting construction is missing");

        int guard = source.lastIndexOf("if (isBlockLightEnabled())", constructor);
        assertTrue(
                guard >= 0 && isInsideBlock(source, guard, constructor),
                "reservoir splatting owns direct block-light SSBOs and must not " +
                        "be constructed when PH_ENABLE_BLOCKLIGHT is disabled"
        );
    }

    @Test
    void reservoirSplattingBinningStagesAreSeparatedByStorageBarriers()
            throws IOException {
        String source = Files.readString(findRepositoryRoot().resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "rendering/restir/splatting/" +
                        "ReservoirSplattingRendering.java"
        ));

        int addPasses = source.indexOf(
                "public IrisPipeline.Builder addPasses("
        );
        int clear = source.indexOf("CLEAR_SHADER", addPasses);
        int clearBarrier = source.indexOf(
                ".thenShaderStorageBarrier(condition)",
                clear
        );
        int reproject = source.indexOf("REPROJECT_SHADER", clearBarrier);
        int reprojectBarrier = source.indexOf(
                ".thenShaderStorageBarrier(condition)",
                reproject
        );
        int cellOffsets = source.indexOf(
                "COMPUTE_CELL_OFFSETS_SHADER",
                reprojectBarrier
        );
        int cellOffsetsBarrier = source.indexOf(
                ".thenShaderStorageBarrier(condition)",
                cellOffsets
        );
        int sort = source.indexOf("SORT_SHADER", cellOffsetsBarrier);
        int sortBarrier = source.indexOf(
                ".thenShaderStorageBarrier(condition)",
                sort
        );
        int methodEnd = source.indexOf("@Override", addPasses);

        assertTrue(addPasses >= 0, "reservoir splatting pass builder is missing");
        assertTrue(clear >= 0 && clearBarrier > clear);
        assertTrue(reproject > clearBarrier);
        assertTrue(reprojectBarrier > reproject);
        assertTrue(cellOffsets > reprojectBarrier);
        assertTrue(cellOffsetsBarrier > cellOffsets);
        assertTrue(sort > cellOffsetsBarrier);
        assertTrue(
                sortBarrier > sort && sortBarrier < methodEnd,
                "sort output must be made visible before temporal reuse reads it"
        );
    }

    @Test
    void directReconnectionConsumersAreSeparatedByStorageBarriers()
            throws IOException {
        String source = Files.readString(findRepositoryRoot().resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));

        int temporal = source.indexOf(".deferredPass(\"temporal reuse\"");
        int temporalBarrier = source.indexOf(
                ".thenShaderStorageBarrier(this::isBlockLightEnabled)",
                temporal
        );
        int spatial = source.indexOf(".deferredPass(\"spatial reuse\"", temporal);
        int spatialBarrier = source.indexOf(
                ".thenShaderStorageBarrier(",
                spatial
        );
        int resolve = source.indexOf(".deferredPass(\"diffuse\"", spatial);

        assertTrue(temporal >= 0 && temporalBarrier > temporal);
        assertTrue(spatial > temporalBarrier);
        assertTrue(spatialBarrier > spatial && spatialBarrier < resolve);
    }

    @Test
    void powerRisAndRegirProducersAreSeparatedByStorageBarriers()
            throws IOException {
        Path root = findRepositoryRoot();
        String powerSampler = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "rendering/restir/power/LightPowerSampler.java"
        ));
        int buildPdf = powerSampler.indexOf("BUILD_POWER_PDF_SHADER");
        int pdfBarrier = powerSampler.indexOf(
                ".thenShaderStorageBarrier(condition)",
                buildPdf
        );
        int presample = powerSampler.indexOf(
                "PRESAMPLE_LOCAL_RIS_SHADER",
                pdfBarrier
        );
        int presampleBarrier = powerSampler.indexOf(
                ".thenShaderStorageBarrier(condition)",
                presample
        );

        String pipeline = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));
        int regir = pipeline.indexOf("ReGIRRendering.BUILD_SHADER");
        int regirBarrier = pipeline.indexOf(
                ".thenShaderStorageBarrier(this::isReGIREnabled)",
                regir
        );
        int initialDirect = pipeline.indexOf(
                ".deferredPass(\"initial direct\"",
                regirBarrier
        );

        assertTrue(buildPdf >= 0 && pdfBarrier > buildPdf);
        assertTrue(presample > pdfBarrier);
        assertTrue(presampleBarrier > presample);
        assertTrue(regir >= 0 && regirBarrier > regir);
        assertTrue(
                initialDirect > regirBarrier,
                "ReGIR output must be visible before initial direct consumes it"
        );
    }

    @Test
    void indirectReservoirNormalizationRejectsNonPositiveSampleWeight()
            throws IOException {
        Path shader = findRepositoryRoot().resolve(
                "modules/shaders/photonics/rendering/restir/indirect/reservoir.glsl"
        );
        String source = Files.readString(shader);
        int finalize = source.indexOf("void indirect_reservoir_finalize_weight(");
        int guard = source.indexOf("if (sample_weight <= 0.0f)", finalize);
        int assignment = source.indexOf("reservoir.weight = 0.0f;", guard);
        int returnStatement = source.indexOf("return;", assignment);
        int normalization = source.indexOf(
                "reservoir.weight = (1.0f / sample_weight)",
                returnStatement
        );

        assertTrue(finalize >= 0, "indirect reservoir normalization is missing");
        assertTrue(guard > finalize);
        assertTrue(assignment > guard);
        assertTrue(returnStatement > assignment);
        assertTrue(
                normalization > returnStatement,
                "normalization must occur only after the invalid-weight guard"
        );
    }

    @Test
    void sceneStreamingInvalidatesReservoirHistoryWithoutSnapshots()
            throws IOException {
        Path root = findRepositoryRoot();
        String pipeline = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));
        String lifecycle = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "rendering/restir/splatting/" +
                        "ReservoirSplattingRendering.java"
        ));
        String extension = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/AbstractPhotonicsExtension.java"
        ));
        String componentLifecycle = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "rendering/AbstractRenderingComponent.java"
        ));

        assertTrue(pipeline.contains("lightList::contentGeneration"));
        assertTrue(pipeline.contains("worldCompiler::contentGeneration"));
        int historyValid = lifecycle.indexOf(
                "historyValid = hasCompletedFrame &&"
        );
        assertTrue(
                historyValid >= 0,
                "ScatterOnly history must be guarded by the frame lifecycle"
        );
        int historyEnd = lifecycle.indexOf(';', historyValid);
        String historyExpression = lifecycle.substring(historyValid, historyEnd);
        assertTrue(
                historyExpression.contains("currentLightContentGeneration") &&
                        historyExpression.contains(
                                "previousLightContentGeneration"
                        ) &&
                        historyExpression.contains(
                                "currentWorldContentGeneration"
                        ) &&
                        historyExpression.contains(
                                "previousWorldContentGeneration"
                        ),
                "history must be invalidated when either GPU-visible content " +
                        "generation changes because no previous snapshot exists"
        );

        int worldRegistration = extension.indexOf(
                "worldCompiler = registerComponent("
        );
        int lightRegistration = extension.indexOf(
                "lightList = registerComponent("
        );
        assertTrue(
                worldRegistration >= 0 && lightRegistration > worldRegistration,
                "world and light uploads must precede reservoir history " +
                        "validation in component registration order"
        );
        assertTrue(componentLifecycle.contains(
                "components.forEach(RenderingComponent::onFrameBegin)"
        ));
    }

    private static boolean isInsideBlock(
            String source,
            int openingStatement,
            int target
    ) {
        int openingBrace = source.indexOf('{', openingStatement);
        if (openingBrace < 0 || openingBrace > target) return false;

        int depth = 0;
        for (int i = openingBrace; i < target; i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return false;
            }
        }
        return depth > 0;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("modules/core/src/main/java");
            if (Files.isDirectory(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository root");
    }
}
