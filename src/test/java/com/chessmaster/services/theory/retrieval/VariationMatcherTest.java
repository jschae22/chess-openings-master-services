package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.theory.OpeningVariation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VariationMatcherTest {

    private static final OpeningVariation NAJDORF = variation("Najdorf Variation",
            "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6");
    private static final OpeningVariation ENGLISH_ATTACK = variation("English Attack",
            "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6", "Be3");
    private static final OpeningVariation DRAGON = variation("Dragon Variation",
            "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "g6");

    private static final List<OpeningVariation> ALL = List.of(ENGLISH_ATTACK, DRAGON, NAJDORF);

    @Test
    void matchesVariationAndItsAncestorsMostSpecificLast() {
        List<OpeningVariation> path = VariationMatcher.matchingPath(ALL, List.of(
                "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6", "Be3", "e5"));

        assertThat(path).extracting(OpeningVariation::getName)
                .containsExactly("Najdorf Variation", "English Attack");
    }

    @Test
    void stopsAtTheDeepestVariationReachedSoFar() {
        List<OpeningVariation> path = VariationMatcher.matchingPath(ALL, List.of(
                "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6"));

        assertThat(path).extracting(OpeningVariation::getName).containsExactly("Najdorf Variation");
    }

    @Test
    void noMatchBeforeAVariationIsFullyPlayed() {
        assertThat(VariationMatcher.matchingPath(ALL, List.of("e4", "c5", "Nf3", "d6"))).isEmpty();
    }

    @Test
    void divergingLineDoesNotMatch() {
        List<OpeningVariation> path = VariationMatcher.matchingPath(ALL, List.of(
                "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "g6"));

        assertThat(path).extracting(OpeningVariation::getName).containsExactly("Dragon Variation");
    }

    @Test
    void ignoresCheckAndAnnotationSuffixes() {
        OpeningVariation ruy = variation("Ruy Lopez Main", "e4", "e5", "Nf3", "Nc6", "Bb5+");

        assertThat(VariationMatcher.matchingPath(List.of(ruy),
                List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"))).containsExactly(ruy);
    }

    @Test
    void nullMoveInHistoryMatchesNothingInsteadOfThrowing() {
        assertThat(VariationMatcher.matchingPath(ALL, java.util.Arrays.asList("e4", null, "Nf3"))).isEmpty();
    }

    @Test
    void emptyOrMissingHistoryMatchesNothing() {
        assertThat(VariationMatcher.matchingPath(ALL, List.of())).isEmpty();
        assertThat(VariationMatcher.matchingPath(ALL, null)).isEmpty();
    }

    private static OpeningVariation variation(String name, String... moves) {
        return new OpeningVariation(null, null, name, List.of(moves));
    }
}
