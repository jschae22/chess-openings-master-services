package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.theory.OpeningVariation;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic step of retrieval: finds which known variations the played moves
 * are inside, by checking whether each variation's identifying moves are a prefix
 * of the move history. No embeddings involved.
 */
final class VariationMatcher {

    private VariationMatcher() {
    }

    /**
     * Every variation whose identifying moves are a prefix of {@code moveHistory},
     * ordered from least to most specific. Since sub-variations extend their
     * parent's moves, this is the matched line plus all of its ancestors.
     */
    static List<OpeningVariation> matchingPath(List<OpeningVariation> variations, List<String> moveHistory) {
        if (moveHistory == null || moveHistory.isEmpty()) {
            return List.of();
        }
        // Clients can send null/blank entries; they match nothing rather than failing the request.
        List<String> played = moveHistory.stream()
                .map(m -> m == null ? "" : normalize(m))
                .toList();
        return variations.stream()
                .filter(v -> isPrefix(v.getIdentifyingMoves(), played))
                .sorted(Comparator.comparingInt(v -> v.getIdentifyingMoves().size()))
                .toList();
    }

    private static boolean isPrefix(List<String> prefix, List<String> played) {
        if (prefix.isEmpty() || prefix.size() > played.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!normalize(prefix.get(i)).equals(played.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Drops check/mate/annotation suffixes so "Bb5+" and "Bb5" compare equal. */
    static String normalize(String san) {
        return san.replaceAll("[+#!?]+$", "");
    }
}
