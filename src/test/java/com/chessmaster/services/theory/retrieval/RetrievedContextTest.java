package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedContextTest {

    private static final RetrievedChunk LINE_CHUNK =
            new RetrievedChunk(1, 10L, "idea", "text", "theory/x.yaml", 0.85);
    private static final RetrievedChunk OPENING_CHUNK =
            new RetrievedChunk(2, null, "overview", "text", "theory/x.yaml", 0.80);

    @Test
    void groundedWhenVariationMatchedAndALineChunkIsPresent() {
        assertThat(RetrievedContext.found("Sicilian Defense", "Najdorf", List.of(OPENING_CHUNK, LINE_CHUNK)).status())
                .isEqualTo(Status.GROUNDED);
    }

    @Test
    void partialWhenOnlyOpeningLevelChunksCameBack() {
        // The matched variation has no text of its own, so naming it as "the matched line" would overclaim.
        assertThat(RetrievedContext.found("Sicilian Defense", "Najdorf", List.of(OPENING_CHUNK)).status())
                .isEqualTo(Status.PARTIAL);
    }

    @Test
    void partialWhenNoVariationMatched() {
        assertThat(RetrievedContext.found("Sicilian Defense", null, List.of(LINE_CHUNK)).status())
                .isEqualTo(Status.PARTIAL);
    }

    @Test
    void partialWhenNothingFound() {
        assertThat(RetrievedContext.found("Sicilian Defense", "Najdorf", List.of()).status())
                .isEqualTo(Status.PARTIAL);
    }
}
