package com.chessmaster.services.chat;

import com.chessmaster.services.opening.Opening;
import com.chessmaster.services.theory.retrieval.RetrievedContext;
import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeServiceTheorySectionTest {

    private static final List<RetrievedChunk> CHUNKS =
            List.of(new RetrievedChunk(1, 10L, "idea", "Najdorf idea text", "theory/s.yaml", 0.2));

    @Test
    void groundedIncludesNotesAndMatchedLine() {
        String section = ClaudeService.theorySection(
                RetrievedContext.found("Sicilian Defense", Opening.Side.BLACK, "Najdorf Variation", CHUNKS));

        assertThat(section).contains("matched line: Najdorf Variation").contains("Najdorf idea text");
    }

    @Test
    void partialWarnsThatTheLineIsNotCovered() {
        String section = ClaudeService.theorySection(
                RetrievedContext.found("Sicilian Defense", Opening.Side.BLACK, null, CHUNKS));

        assertThat(section).contains("do not cover the student's exact line").contains("Najdorf idea text");
    }

    @Test
    void sideLineNamesTheStudentsColour() {
        assertThat(ClaudeService.sideLine(Opening.Side.BLACK)).contains("plays Black").contains("Black's perspective");
        assertThat(ClaudeService.sideLine(Opening.Side.WHITE)).contains("plays White");
        assertThat(ClaudeService.sideLine(null)).isEmpty();
    }

    @Test
    void userMessageCarriesTheSideOnItsOwnLine() {
        ChatRequest request = new ChatRequest("London System", List.of("d4", "d5"), "fen", "What now?");
        String white = ClaudeService.userMessage(request,
                RetrievedContext.found("London System", Opening.Side.WHITE, null, CHUNKS));
        String black = ClaudeService.userMessage(request,
                RetrievedContext.found("London System", Opening.Side.BLACK, null, CHUNKS));

        assertThat(white).contains("\nThe student plays White in this opening")
                .contains("Move history so far: d4, d5.").contains("Student's question: What now?");
        assertThat(black).contains("The student plays Black in this opening");
    }

    @Test
    void userMessageOmitsTheSideWhenTheOpeningIsUnknownOrMissing() {
        ChatRequest unknown = new ChatRequest("Made-up Opening", null, null, "Hi");
        ChatRequest missing = new ChatRequest(null, List.of(), null, "Hi");

        for (ChatRequest request : List.of(unknown, missing)) {
            String message = ClaudeService.userMessage(request, RetrievedContext.UNKNOWN_OPENING);
            assertThat(message).doesNotContain("The student plays").doesNotContain(". \n")
                    .contains("No moves played yet").contains("No theory notes are available");
        }
        assertThat(ClaudeService.userMessage(missing, RetrievedContext.UNKNOWN_OPENING))
                .contains("an unknown opening");
    }

    @Test
    void errorStatusStillCarriesTheSide() {
        ChatRequest request = new ChatRequest("London System", List.of(), null, "Hi");
        String message = ClaudeService.userMessage(request,
                RetrievedContext.error("London System", Opening.Side.WHITE, null));

        assertThat(message).contains("The student plays White").contains("could not be loaded");
    }

    @Test
    void partialWithNoChunksIsTreatedAsNoNotes() {
        String section = ClaudeService.theorySection(
                RetrievedContext.found("Sicilian Defense", Opening.Side.BLACK, null, List.of()));

        assertThat(section).contains("No theory notes are available").doesNotContain("---");
    }

    @Test
    void ungroundedCasesTellClaudeToDiscloseIt() {
        assertThat(ClaudeService.theorySection(RetrievedContext.UNKNOWN_OPENING))
                .contains("No theory notes are available");
        assertThat(ClaudeService.theorySection(RetrievedContext.error("Sicilian Defense", Opening.Side.BLACK, null)))
                .contains("could not be loaded");
    }
}
