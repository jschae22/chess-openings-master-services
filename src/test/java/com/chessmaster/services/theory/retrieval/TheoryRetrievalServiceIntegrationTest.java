package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.theory.ingestion.VoyageEmbeddingClient;
import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Runs the real retrieval SQL against pgvector (the same database the context-load
 * tests need) with Voyage stubbed. Each test seeds its own opening and vectors, so
 * which chunks are nearest is controlled here, not by embedding quality. Everything
 * rolls back, so the ingested corpus is untouched.
 *
 * <p>Vectors are unit vectors in the first two dimensions, at an angle from the
 * query vector [1, 0, ...]; cosine distance is then {@code 1 - cos(angle)}, so a
 * smaller angle means a nearer chunk.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class TheoryRetrievalServiceIntegrationTest {

    private static final String OPENING = "Retrieval Test Opening";
    private static final List<String> LINE = List.of("e4", "c5", "Nf3", "d6");
    private static final List<String> SUB_LINE = List.of("e4", "c5", "Nf3", "d6", "d4");

    @Autowired TheoryRetrievalService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean VoyageEmbeddingClient embeddings;

    private long openingId;
    private long lineId;

    @BeforeEach
    void seed() {
        float[] query = new float[VoyageEmbeddingClient.EMBEDDING_DIMENSION];
        query[0] = 1f;
        when(embeddings.embedQuery(anyString())).thenReturn(query);

        openingId = jdbc.queryForObject("""
                INSERT INTO openings (name, description, side, moves)
                VALUES (?, 'test', 'BLACK', CAST('["e4","c5"]' AS jsonb)) RETURNING id""",
                Long.class, OPENING);
        lineId = variation(null, "Test Line", LINE);
        variation(lineId, "Test Sub Line", SUB_LINE);
    }

    @Test
    void reservesSlotsForTheMatchedLineEvenWhenOpeningChunksAreNearer() {
        // Five opening-level chunks, all nearer than the line's only chunk.
        for (int i = 0; i < 5; i++) {
            chunk(null, "opening_" + i, 0.1 + i * 0.01);
        }
        chunk(lineId, "line_idea", 1.2);

        RetrievedContext context = service.retrieve(OPENING, LINE, "what is the plan?");

        assertThat(context.status()).isEqualTo(Status.GROUNDED);
        assertThat(context.variationName()).isEqualTo("Test Line");
        assertThat(context.chunks()).hasSize(5);
        assertThat(context.chunks()).extracting(RetrievedChunk::section).contains("line_idea");
    }

    @Test
    void resultsAreOrderedNearestFirst() {
        chunk(null, "far_opening", 1.0);
        chunk(null, "near_opening", 0.2);
        chunk(lineId, "mid_line", 0.5);

        RetrievedContext context = service.retrieve(OPENING, LINE, "q");

        assertThat(context.chunks()).extracting(RetrievedChunk::section)
                .containsExactly("near_opening", "mid_line", "far_opening");
        assertThat(context.chunks()).extracting(RetrievedChunk::distance).isSorted();
    }

    @Test
    void matchedLineWithNoChunksOfItsOwnIsOnlyPartial() {
        chunk(null, "overview", 0.3);
        chunk(null, "why_main_line", 0.4);

        RetrievedContext context = service.retrieve(OPENING, LINE, "q");

        assertThat(context.variationName()).isNotNull();
        assertThat(context.chunks()).isNotEmpty().allMatch(c -> c.variationId() == null);
        assertThat(context.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    void ancestorChunksCountAsLineContentForADeeperMatch() {
        chunk(lineId, "parent_idea", 0.3);
        chunk(null, "overview", 0.2);

        RetrievedContext context = service.retrieve(OPENING, SUB_LINE, "q");

        assertThat(context.variationName()).isEqualTo("Test Sub Line");
        assertThat(context.chunks()).extracting(RetrievedChunk::section).contains("parent_idea");
        assertThat(context.status()).isEqualTo(Status.GROUNDED);
    }

    @Test
    void siblingLineChunksAreNotReturned() {
        long sibling = variation(null, "Other Line", List.of("e4", "c5", "Nc3"));
        chunk(sibling, "sibling_idea", 0.1);
        chunk(lineId, "line_idea", 0.9);

        RetrievedContext context = service.retrieve(OPENING, LINE, "q");

        assertThat(context.chunks()).extracting(RetrievedChunk::section)
                .contains("line_idea").doesNotContain("sibling_idea");
    }

    @Test
    void noLineMatchSearchesOpeningLevelChunksOnly() {
        chunk(null, "overview", 0.3);
        chunk(lineId, "line_idea", 0.1);

        RetrievedContext context = service.retrieve(OPENING, List.of("e4", "c5"), "q");

        assertThat(context.status()).isEqualTo(Status.PARTIAL);
        assertThat(context.variationName()).isNull();
        assertThat(context.chunks()).extracting(RetrievedChunk::section).containsExactly("overview");
    }

    @Test
    void nullMoveInHistoryStillRetrievesOpeningLevelContent() {
        chunk(null, "overview", 0.3);

        RetrievedContext context = service.retrieve(OPENING, Arrays.asList("e4", null), "q");

        assertThat(context.status()).isEqualTo(Status.PARTIAL);
        assertThat(context.chunks()).hasSize(1);
    }

    private long variation(Long parentId, String name, List<String> moves) {
        String json = moves.stream().map(m -> "\"" + m + "\"").collect(Collectors.joining(",", "[", "]"));
        return jdbc.queryForObject("""
                INSERT INTO opening_variations (opening_id, parent_variation_id, name, identifying_moves)
                VALUES (?, ?, ?, CAST(? AS jsonb)) RETURNING id""",
                Long.class, openingId, parentId, name, json);
    }

    private void chunk(Long variationId, String section, double angle) {
        StringBuilder vector = new StringBuilder("[").append((float) Math.cos(angle))
                .append(',').append((float) Math.sin(angle));
        for (int i = 2; i < VoyageEmbeddingClient.EMBEDDING_DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');
        jdbc.update("""
                INSERT INTO theory_chunks (opening_id, variation_id, section, content, content_hash, embedding, source)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), 'test')""",
                openingId, variationId, section, "content of " + section,
                "hash-" + section + "-" + variationId, vector.toString());
    }
}
