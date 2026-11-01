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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds the theory text that should ground a coach answer.
 *
 * <p>Two steps: (1) deterministically match the move history against the
 * opening's variation tree, then (2) embed the question and take the nearest
 * chunks, up to {@code retrieval.top-k} in total. Up to
 * {@code retrieval.variation-slots} of those are reserved for chunks of the
 * matched variation and its ancestors, because Voyage distances on this corpus
 * are too uniform for the line's own text to reliably outrank generic
 * opening-level text; the remaining slots go to the nearest opening-level
 * chunks.
 *
 * <p>Retrieval is best-effort: any failure (database or embedding call) yields
 * {@link RetrievedContext.Status#ERROR} so the chat still works, just ungrounded.
 */
@Service
public class TheoryRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(TheoryRetrievalService.class);

    private final OpeningRepository openingRepository;
    private final OpeningVariationRepository variationRepository;
    private final VoyageEmbeddingClient embeddings;
    private final JdbcTemplate jdbc;
    private final int topK;
    private final int variationSlots;

    public TheoryRetrievalService(OpeningRepository openingRepository,
                                  OpeningVariationRepository variationRepository,
                                  VoyageEmbeddingClient embeddings,
                                  JdbcTemplate jdbc,
                                  @Value("${retrieval.top-k:5}") int topK,
                                  @Value("${retrieval.variation-slots:3}") int variationSlots) {
        this.openingRepository = openingRepository;
        this.variationRepository = variationRepository;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.topK = topK;
        this.variationSlots = Math.min(variationSlots, topK);
    }

    public RetrievedContext retrieve(String openingName, List<String> moveHistory, String question) {
        if (openingName == null || openingName.isBlank() || question == null || question.isBlank()) {
            return RetrievedContext.UNKNOWN_OPENING;
        }
        String resolvedName = null;
        String variationName = null;
        try {
            Opening opening = openingRepository.findByName(openingName).orElse(null);
            if (opening == null) {
                return RetrievedContext.UNKNOWN_OPENING;
            }
            resolvedName = opening.getName();

            List<OpeningVariation> path = VariationMatcher.matchingPath(
                    variationRepository.findByOpeningId(opening.getId()), moveHistory);
            variationName = path.isEmpty() ? null : path.get(path.size() - 1).getName();

            List<RetrievedChunk> chunks = nearestChunks(opening.getId(), path, question);
            return RetrievedContext.found(resolvedName, variationName, chunks);
        } catch (RuntimeException e) {
            log.warn("Theory retrieval failed for \"{}\"; answering without context", openingName, e);
            return RetrievedContext.error(resolvedName, variationName);
        }
    }

    private List<RetrievedChunk> nearestChunks(long openingId, List<OpeningVariation> path, String question) {
        String vector = vectorLiteral(embeddings.embedQuery(question));

        List<RetrievedChunk> chunks = new ArrayList<>();
        if (!path.isEmpty() && variationSlots > 0) {
            // Ids are numeric database keys, so inlining them is injection-safe.
            String ids = path.stream().map(v -> String.valueOf(v.getId())).collect(Collectors.joining(","));
            chunks.addAll(query("c.variation_id IN (" + ids + ")", vector, openingId, variationSlots));
        }
        chunks.addAll(query("c.variation_id IS NULL", vector, openingId, topK - chunks.size()));

        chunks.sort(Comparator.comparingDouble(RetrievedChunk::distance));
        return chunks;
    }

    private List<RetrievedChunk> query(String filter, String vector, long openingId, int limit) {
        return jdbc.query("""
                        SELECT c.id, c.variation_id, c.section, c.content, c.source,
                               c.embedding <=> CAST(? AS vector) AS distance
                          FROM theory_chunks c
                         WHERE c.opening_id = ? AND %s
                         ORDER BY distance
                         LIMIT ?""".formatted(filter),
                (rs, i) -> new RetrievedChunk(rs.getLong("id"), rs.getObject("variation_id", Long.class),
                        rs.getString("section"), rs.getString("content"), rs.getString("source"),
                        rs.getDouble("distance")),
                vector, openingId, limit);
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
