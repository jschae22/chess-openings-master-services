package com.chessmaster.services.theory.ingestion;

import java.util.List;

/**
 * One parsed {@code theory/<slug>.yaml} file, flattened into the rows ingestion
 * needs: the variation tree and the retrievable text chunks. Produced by
 * {@link TheoryCorpusParser} with no database or network access so it can be
 * unit-tested directly.
 */
public record ParsedOpening(
        String openingName,
        String slug,
        String source,
        List<ParsedVariation> variations,
        List<ParsedChunk> chunks) {

    /**
     * A node in the variation tree. {@code parentIdentifyingMoves} is null for a
     * top-level variation; otherwise it is the {@code identifyingMoves} of the
     * parent, which the parser guarantees appears earlier in the list.
     */
    public record ParsedVariation(
            String name,
            List<String> identifyingMoves,
            List<String> parentIdentifyingMoves) {
    }

    /**
     * One retrievable unit of theory text. {@code variationIdentifyingMoves} is
     * null for opening-level content (overview, main-line rationale); otherwise
     * it identifies the owning variation. {@code section} is the raw YAML key the
     * text came from.
     */
    public record ParsedChunk(
            List<String> variationIdentifyingMoves,
            String section,
            String text) {
    }
}
