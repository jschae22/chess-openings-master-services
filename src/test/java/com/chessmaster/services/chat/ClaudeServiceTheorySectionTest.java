package com.chessmaster.services.chat;

import com.chessmaster.services.theory.retrieval.RetrievedContext;
import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeServiceTheorySectionTest {

    private static final List<RetrievedChunk> CHUNKS =
            List.of(new RetrievedChunk(1, "idea", "Najdorf idea text", "theory/s.yaml", 0.2));

    @Test
    void groundedIncludesNotesAndMatchedLine() {
        String section = ClaudeService.theorySection(
                RetrievedContext.found("Sicilian Defense", "Najdorf Variation", CHUNKS));

        assertThat(section).contains("matched line: Najdorf Variation").contains("Najdorf idea text");
    }

    @Test
    void partialWarnsThatTheLineIsNotCovered() {
        String section = ClaudeService.theorySection(
                RetrievedContext.found("Sicilian Defense", null, CHUNKS));

        assertThat(section).contains("not in our notes").contains("Najdorf idea text");
    }

    @Test
    void ungroundedCasesTellClaudeToDiscloseIt() {
        assertThat(ClaudeService.theorySection(RetrievedContext.UNKNOWN_OPENING))
                .contains("No theory notes are available");
        assertThat(ClaudeService.theorySection(RetrievedContext.error("Sicilian Defense", null)))
                .contains("could not be loaded");
    }
}
