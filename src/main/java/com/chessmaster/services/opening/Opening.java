package com.chessmaster.services.opening;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "openings")
public class Opening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Side side;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> moves;

    public enum Side { WHITE, BLACK }

    // Getters

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Side getSide() { return side; }
    public List<String> getMoves() { return moves; }
}
