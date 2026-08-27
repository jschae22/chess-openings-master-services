package com.chessmaster.services.theory;

import com.chessmaster.services.opening.Opening;
import jakarta.persistence.*;

/**
 * The {@code embedding vector(1024)} column is intentionally not mapped here:
 * ingestion writes it and retrieval reads it through native SQL using pgvector's
 * distance operators, which JPA cannot express.
 */
@Entity
@Table(name = "theory_chunks")
public class TheoryChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opening_id", nullable = false)
    private Opening opening;

    // Null for opening-level content that isn't tied to a specific variation.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variation_id")
    private OpeningVariation variation;

    @Column(nullable = false, length = 64)
    private String section;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 255)
    private String source;

    protected TheoryChunk() {
    }

    public TheoryChunk(Opening opening, OpeningVariation variation, String section, String content, String source) {
        this.opening = opening;
        this.variation = variation;
        this.section = section;
        this.content = content;
        this.source = source;
    }

    public Long getId() { return id; }
    public Opening getOpening() { return opening; }
    public OpeningVariation getVariation() { return variation; }
    public String getSection() { return section; }
    public String getContent() { return content; }
    public String getSource() { return source; }
}
