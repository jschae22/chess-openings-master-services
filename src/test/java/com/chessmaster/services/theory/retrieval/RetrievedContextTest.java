package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedContextTest {

    private static final List<RetrievedChunk> CHUNKS =
            List.of(new RetrievedChunk(1, "idea", "text", "theory/x.yaml", 0.85));

    @Test
    void groundedWhenVariationMatchedAndChunksFound() {
        assertThat(RetrievedContext.found("Sicilian Defense", "Najdorf", CHUNKS).status())
                .isEqualTo(Status.GROUNDED);
    }

    @Test
    void partialWhenNoVariationMatched() {
        assertThat(RetrievedContext.found("Sicilian Defense", null, CHUNKS).status())
                .isEqualTo(Status.PARTIAL);
    }

    @Test
    void partialWhenNothingFound() {
        assertThat(RetrievedContext.found("Sicilian Defense", "Najdorf", List.of()).status())
                .isEqualTo(Status.PARTIAL);
    }
}
