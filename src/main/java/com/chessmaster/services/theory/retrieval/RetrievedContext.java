package com.chessmaster.services.theory.retrieval;

import java.util.List;

/**
 * What retrieval found for one chat request.
 *
 * @param status        how well the answer can be grounded in the corpus
 * @param openingName   canonical opening name, or null if the request's opening isn't in the corpus
 * @param variationName most specific variation the move history is inside, or null if none matched
 * @param chunks        theory chunks ranked by relevance to the question (best first)
 */
public record RetrievedContext(Status status, String openingName, String variationName,
                               List<RetrievedChunk> chunks) {

    public enum Status {
        /** The line is in the corpus and relevant theory was found. */
        GROUNDED,
        /** Opening is covered, but the exact line isn't or the best chunk is only loosely relevant. */
        PARTIAL,
        /** The opening isn't in the corpus (or the request didn't name one). */
        UNKNOWN_OPENING,
        /** Retrieval itself failed (embedding call or database error). */
        ERROR
    }

    public static final RetrievedContext UNKNOWN_OPENING =
            new RetrievedContext(Status.UNKNOWN_OPENING, null, null, List.of());

    public static RetrievedContext error(String openingName, String variationName) {
        return new RetrievedContext(Status.ERROR, openingName, variationName, List.of());
    }

    /**
     * Classifies a successful search: grounded when the played line matched a known
     * variation and chunks came back; otherwise partial. Chunk distance is deliberately
     * not used: Voyage cosine distances for this corpus cluster at 0.8-1.0 for on-topic
     * and off-topic questions alike, so a threshold cannot tell them apart.
     */
    public static RetrievedContext found(String openingName, String variationName,
                                         List<RetrievedChunk> chunks) {
        Status status = (variationName != null && !chunks.isEmpty()) ? Status.GROUNDED : Status.PARTIAL;
        return new RetrievedContext(status, openingName, variationName, chunks);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /** @param distance pgvector cosine distance to the question (lower = closer) */
    public record RetrievedChunk(long id, String section, String content, String source, double distance) {
    }
}
