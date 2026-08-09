package at.redi2go.photonics.common.iris;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniformPatcherTest {
    @Test
    void generatedRequiredUniformsAreDeduplicatedAcrossIncludes()
            throws CommandSyntaxException {
        UniformPatcher.prepare();

        String source = """
                #version 430
                //ph_required: uniform mat4 gbufferPreviousModelView;
                //ph_required: uniform mat4 gbufferPreviousProjection;
                //ph_required: uniform mat4 gbufferPreviousModelView, gbufferProjection;
                """;

        String patched = UniformPatcher.addRequiredUniforms(source);

        assertEquals(1, occurrencesOf(patched, "gbufferPreviousModelView"));
        assertEquals(1, occurrencesOf(patched, "gbufferPreviousProjection"));
        assertEquals(1, occurrencesOf(patched, "gbufferProjection"));
    }

    private static int occurrencesOf(String text, String term) {
        int occurrences = 0;
        int offset = 0;
        while (true) {
            int index = text.indexOf(term, offset);
            if (index < 0) return occurrences;

            occurrences++;
            offset = index + term.length();
        }
    }
}
