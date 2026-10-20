package com.chessmaster.services.theory.ingestion;

import java.util.ArrayList;
import java.util.List;

/** Mutable tally of what an ingestion run did, logged as a summary at the end. */
public class IngestionReport {

    private int filesProcessed;
    private final List<String> skippedOpenings = new ArrayList<>();
    private int variationsInserted;
    private int variationsKept;
    private int variationsDeleted;
    private int chunksInserted;
    private int chunksUpdated;
    private int chunksUnchanged;
    private int chunksDeleted;

    void fileProcessed() { filesProcessed++; }
    void fileSkipped(String openingName) { skippedOpenings.add(openingName); }
    void variationInserted() { variationsInserted++; }
    void variationKept() { variationsKept++; }
    void variationDeleted() { variationsDeleted++; }
    void chunkInserted() { chunksInserted++; }
    void chunkUpdated() { chunksUpdated++; }
    void chunkUnchanged() { chunksUnchanged++; }
    void chunkDeleted() { chunksDeleted++; }

    int chunksEmbedded() { return chunksInserted + chunksUpdated; }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("  files:      ").append(filesProcessed).append(" processed");
        if (!skippedOpenings.isEmpty()) {
            sb.append(", skipped (no openings row): ").append(skippedOpenings);
        }
        sb.append('\n');
        sb.append("  variations: ").append(variationsInserted).append(" inserted, ")
          .append(variationsKept).append(" kept, ")
          .append(variationsDeleted).append(" deleted\n");
        sb.append("  chunks:     ").append(chunksInserted).append(" inserted, ")
          .append(chunksUpdated).append(" updated, ")
          .append(chunksUnchanged).append(" unchanged, ")
          .append(chunksDeleted).append(" deleted\n");
        sb.append("  embeddings: ").append(chunksEmbedded()).append(" texts sent to Voyage");
        return sb.toString();
    }
}
