package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.opening.Opening;
import com.chessmaster.services.opening.OpeningRepository;
import com.chessmaster.services.theory.OpeningVariation;
import com.chessmaster.services.theory.OpeningVariationRepository;
import com.chessmaster.services.theory.ingestion.VoyageEmbeddingClient;
import com.chessmaster.services.theory.retrieval.RetrievedContext.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds the theory text that should ground a coach answer.
 *
 * <p>Two steps: (1) deterministically match the move history against the
 * opening's variation tree, then (2) embed the question and take the nearest
 * {@code retrieval.top-k} chunks among opening-level content plus the matched
 * variation and its ancestors. Retrieval is best-effort: if the embedding call
 * fails the chat still works, just ungrounded.
 */
@Service
public class TheoryRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(TheoryRetrievalService.class);

    private final OpeningRepository openingRepository;
    private final OpeningVariationRepository variationRepository;
    private final VoyageEmbeddingClient embeddings;
    private final JdbcTemplate jdbc;
    private final int topK;

    public TheoryRetrievalService(OpeningRepository openingRepository,
                                  OpeningVariationRepository variationRepository,
                                  VoyageEmbeddingClient embeddings,
                                  JdbcTemplate jdbc,
                                  @Value("${retrieval.top-k:5}") int topK) {
        this.openingRepository = openingRepository;
        this.variationRepository = variationRepository;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.topK = topK;
    }

    public RetrievedContext retrieve(String openingName, List<String> moveHistory, String question) {
        if (openingName == null || openingName.isBlank() || question == null || question.isBlank()) {
            return RetrievedContext.UNKNOWN_OPENING;
        }
        Opening opening = openingRepository.findByName(openingName).orElse(null);
        if (opening == null) {
            return RetrievedContext.UNKNOWN_OPENING;
        }

        List<OpeningVariation> path = VariationMatcher.matchingPath(
                variationRepository.findByOpeningId(opening.getId()), moveHistory);
        String variationName = path.isEmpty() ? null : path.get(path.size() - 1).getName();

        try {
            List<RetrievedChunk> chunks = nearestChunks(opening.getId(), path, question);
            return RetrievedContext.found(opening.getName(), variationName, chunks);
        } catch (RuntimeException e) {
            log.warn("Theory retrieval failed for \"{}\"; answering without context", openingName, e);
            return RetrievedContext.error(opening.getName(), variationName);
        }
    }

    private List<RetrievedChunk> nearestChunks(long openingId, List<OpeningVariation> path, String question) {
        String vector = vectorLiteral(embeddings.embedQuery(question));

        // Ids are numeric database keys, so inlining them is injection-safe.
        String variationFilter = path.isEmpty() ? "" : " OR c.variation_id IN ("
                + path.stream().map(v -> String.valueOf(v.getId())).collect(Collectors.joining(",")) + ")";

        return jdbc.query("""
                        SELECT c.id, c.section, c.content, c.source,
                               c.embedding <=> CAST(? AS vector) AS distance
                          FROM theory_chunks c
                         WHERE c.opening_id = ?
                           AND (c.variation_id IS NULL%s)
                         ORDER BY distance
                         LIMIT ?""".formatted(variationFilter),
                (rs, i) -> new RetrievedChunk(rs.getLong("id"), rs.getString("section"),
                        rs.getString("content"), rs.getString("source"), rs.getDouble("distance")),
                vector, openingId, topK);
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
