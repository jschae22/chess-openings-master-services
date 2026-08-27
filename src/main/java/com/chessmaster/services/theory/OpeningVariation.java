package com.chessmaster.services.theory;

import com.chessmaster.services.opening.Opening;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "opening_variations")
public class OpeningVariation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opening_id", nullable = false)
    private Opening opening;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_variation_id")
    private OpeningVariation parent;

    @Column(nullable = false, length = 100)
    private String name;

    // Full SAN sequence from move 1, e.g. ["e4","e5","Nf3","Nc6","Bc4","Bc5"].
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "identifying_moves", nullable = false, columnDefinition = "jsonb")
    private List<String> identifyingMoves;

    protected OpeningVariation() {
    }

    public OpeningVariation(Opening opening, OpeningVariation parent, String name, List<String> identifyingMoves) {
        this.opening = opening;
        this.parent = parent;
        this.name = name;
        this.identifyingMoves = identifyingMoves;
    }

    public Long getId() { return id; }
    public Opening getOpening() { return opening; }
    public OpeningVariation getParent() { return parent; }
    public String getName() { return name; }
    public List<String> getIdentifyingMoves() { return identifyingMoves; }
}
