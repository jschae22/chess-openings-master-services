package com.chessmaster.services.theory.ingestion;

import com.chessmaster.services.opening.Opening;
import com.chessmaster.services.opening.OpeningRepository;
import com.chessmaster.services.theory.OpeningVariation;
import com.chessmaster.services.theory.OpeningVariationRepository;
import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedChunk;
import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedVariation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the YAML theory corpus, embeds it with Voyage, and writes
 * {@code opening_variations} / {@code theory_chunks}.
 *
 * <p>Re-runnable: variations are matched by their identifying-move sequence and
 * chunks by a content hash, so a second run only re-embeds text that actually
 * changed and deletes rows the corpus no longer contains. All embedding happens
 * in one pass so a run makes as few Voyage requests as possible; each opening is
 * then written in its own transaction.
 */
@Service
public class TheoryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TheoryIngestionService.class);
    private static final String CORPUS_LOCATION = "classpath*:theory/*.yaml";

    private final TheoryCorpusParser parser;
    private final VoyageEmbeddingClient embeddings;
    private final OpeningRepository openingRepository;
    private final OpeningVariationRepository variationRepository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public TheoryIngestionService(TheoryCorpusParser parser,
                                  VoyageEmbeddingClient embeddings,
                                  OpeningRepository openingRepository,
                                  OpeningVariationRepository variationRepository,
                                  JdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager) {
        this.parser = parser;
        this.embeddings = embeddings;
        this.openingRepository = openingRepository;
        this.variationRepository = variationRepository;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IngestionReport run() {
        IngestionReport report = new IngestionReport();
        List<OpeningPlan> plans = planAll(report);

        // Embed every new/changed chunk across all openings in one pass.
        List<String> toEmbed = new ArrayList<>();
        for (OpeningPlan plan : plans) {
            for (PlannedChunk chunk : plan.chunks()) {
                if (chunk.needsEmbedding()) {
                    toEmbed.add(chunk.content());
                }
            }
        }
        Map<String, float[]> vectors = new HashMap<>();
        if (!toEmbed.isEmpty()) {
            List<String> distinct = toEmbed.stream().distinct().toList();
            List<float[]> embedded = embeddings.embedDocuments(distinct);
            for (int i = 0; i < distinct.size(); i++) {
                vectors.put(distinct.get(i), embedded.get(i));
            }
        }

        for (OpeningPlan plan : plans) {
            tx.executeWithoutResult(status -> write(plan, vectors, report));
        }
        return report;
    }

    private List<OpeningPlan> planAll(IngestionReport report) {
        Resource[] files;
        try {
            files = resourceResolver.getResources(CORPUS_LOCATION);
        } catch (IOException e) {
            throw new IllegalStateException("Could not list the theory corpus at " + CORPUS_LOCATION, e);
        }
        if (files.length == 0) {
            throw new IllegalStateException("No theory files found at " + CORPUS_LOCATION);
        }

        List<OpeningPlan> plans = new ArrayList<>();
        for (Resource file : files) {
            ParsedOpening parsed = parser.parse(file);
            Opening opening = openingRepository.findByName(parsed.openingName()).orElse(null);
            if (opening == null) {
                log.warn("No 'openings' row named \"{}\" -- skipping {} (seed the opening first)",
                        parsed.openingName(), parsed.source());
                report.fileSkipped(parsed.openingName());
                continue;
            }
            report.fileProcessed();
            plans.add(plan(opening, parsed));
        }
        return plans;
    }

    private OpeningPlan plan(Opening opening, ParsedOpening parsed) {
        Map<ChunkKey, ExistingChunk> existing = loadExistingChunks(opening.getId());

        List<PlannedChunk> chunks = new ArrayList<>();
        for (ParsedChunk pc : parsed.chunks()) {
            List<String> variationMoves = pc.variationIdentifyingMoves();
            String variationName = variationMoves == null ? null
                    : lastVariationName(parsed, variationMoves);
            String content = composeContent(parsed.openingName(), variationName, pc.section(), pc.text());
            String hash = sha256(content);
            ExistingChunk match = existing.get(new ChunkKey(variationMoves, pc.section()));
            chunks.add(new PlannedChunk(variationMoves, pc.section(), content, hash,
                    match == null || !match.contentHash().equals(hash)));
        }
        return new OpeningPlan(opening, parsed, chunks, existing);
    }

    private Map<ChunkKey, ExistingChunk> loadExistingChunks(long openingId) {
        Map<Long, List<String>> movesByVariationId = new HashMap<>();
        for (OpeningVariation v : variationRepository.findByOpeningId(openingId)) {
            movesByVariationId.put(v.getId(), v.getIdentifyingMoves());
        }
        Map<ChunkKey, ExistingChunk> existing = new HashMap<>();
        jdbc.query("SELECT id, variation_id, section, content_hash FROM theory_chunks WHERE opening_id = ?",
                rs -> {
                    Long variationId = rs.getObject("variation_id", Long.class);
                    List<String> moves = variationId == null ? null : movesByVariationId.get(variationId);
                    existing.put(new ChunkKey(moves, rs.getString("section")),
                            new ExistingChunk(rs.getLong("id"), rs.getString("content_hash")));
                },
                openingId);
        return existing;
    }

    private void write(OpeningPlan plan, Map<String, float[]> vectors, IngestionReport report) {
        Opening opening = plan.opening();
        Map<List<String>, OpeningVariation> resolved = upsertVariations(plan.parsed(), opening, report);

        Set<ChunkKey> desiredKeys = new HashSet<>();
        for (PlannedChunk chunk : plan.chunks()) {
            ChunkKey key = new ChunkKey(chunk.variationMoves(), chunk.section());
            desiredKeys.add(key);
            Long variationId = chunk.variationMoves() == null ? null
                    : resolved.get(chunk.variationMoves()).getId();
            ExistingChunk existing = plan.existingChunks().get(key);

            if (existing == null) {
                jdbc.update("""
                        INSERT INTO theory_chunks
                            (opening_id, variation_id, section, content, content_hash, embedding, source)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?)""",
                        opening.getId(), variationId, chunk.section(), chunk.content(), chunk.hash(),
                        vectorLiteral(vectors, chunk), plan.parsed().source());
                report.chunkInserted();
            } else if (!existing.contentHash().equals(chunk.hash())) {
                jdbc.update("""
                        UPDATE theory_chunks
                           SET content = ?, content_hash = ?, embedding = CAST(? AS vector), source = ?
                         WHERE id = ?""",
                        chunk.content(), chunk.hash(), vectorLiteral(vectors, chunk),
                        plan.parsed().source(), existing.id());
                report.chunkUpdated();
            } else {
                report.chunkUnchanged();
            }
        }

        for (Map.Entry<ChunkKey, ExistingChunk> e : plan.existingChunks().entrySet()) {
            if (!desiredKeys.contains(e.getKey())) {
                jdbc.update("DELETE FROM theory_chunks WHERE id = ?", e.getValue().id());
                report.chunkDeleted();
            }
        }
    }

    private Map<List<String>, OpeningVariation> upsertVariations(
            ParsedOpening parsed, Opening opening, IngestionReport report) {

        Map<List<String>, OpeningVariation> existing = new HashMap<>();
        for (OpeningVariation v : variationRepository.findByOpeningId(opening.getId())) {
            existing.put(v.getIdentifyingMoves(), v);
        }

        Map<List<String>, OpeningVariation> resolved = new HashMap<>();
        // parsed.variations() is pre-order, so a parent is always resolved before its children.
        for (ParsedVariation pv : parsed.variations()) {
            OpeningVariation parent = pv.parentIdentifyingMoves() == null
                    ? null
                    : resolved.get(pv.parentIdentifyingMoves());
            OpeningVariation entity = existing.get(pv.identifyingMoves());
            if (entity == null) {
                entity = variationRepository.save(
                        new OpeningVariation(opening, parent, pv.name(), pv.identifyingMoves()));
                report.variationInserted();
            } else {
                entity.updateFrom(parent, pv.name());
                report.variationKept();
            }
            resolved.put(pv.identifyingMoves(), entity);
        }

        // Drop variations (and their chunks) the corpus no longer lists, deepest
        // first so a parent is never removed before its children.
        List<OpeningVariation> stale = new ArrayList<>();
        for (Map.Entry<List<String>, OpeningVariation> e : existing.entrySet()) {
            if (!resolved.containsKey(e.getKey())) {
                stale.add(e.getValue());
            }
        }
        stale.sort((a, b) -> Integer.compare(
                b.getIdentifyingMoves().size(), a.getIdentifyingMoves().size()));
        for (OpeningVariation v : stale) {
            jdbc.update("DELETE FROM theory_chunks WHERE variation_id = ?", v.getId());
            variationRepository.delete(v);
            report.variationDeleted();
        }

        variationRepository.flush();
        return resolved;
    }

    /** Name of the deepest variation on the path identified by {@code moves}. */
    private static String lastVariationName(ParsedOpening parsed, List<String> moves) {
        return parsed.variations().stream()
                .filter(v -> v.identifyingMoves().equals(moves))
                .map(ParsedVariation::name)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "chunk references unknown variation " + moves + " in " + parsed.source()));
    }

    private static float[] vector(Map<String, float[]> vectors, PlannedChunk chunk) {
        float[] v = vectors.get(chunk.content());
        if (v == null) {
            throw new IllegalStateException("no embedding computed for chunk: " + chunk.section());
        }
        return v;
    }

    private static String vectorLiteral(Map<String, float[]> vectors, PlannedChunk chunk) {
        float[] v = vector(vectors, chunk);
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String composeContent(String opening, String variationName,
                                         String section, String text) {
        String label = variationName == null
                ? opening + " -- " + section.replace('_', ' ')
                : opening + ", " + variationName + " -- " + section.replace('_', ' ');
        return label + "\n" + text;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Chunk identity within one opening: owning variation (by moves, null = opening-level) + section. */
    private record ChunkKey(List<String> variationMoves, String section) {
    }

    private record ExistingChunk(long id, String contentHash) {
    }

    private record PlannedChunk(List<String> variationMoves, String section,
                                String content, String hash, boolean needsEmbedding) {
    }

    private record OpeningPlan(Opening opening, ParsedOpening parsed,
                               List<PlannedChunk> chunks,
                               Map<ChunkKey, ExistingChunk> existingChunks) {
    }
}
