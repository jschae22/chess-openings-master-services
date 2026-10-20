package com.chessmaster.services.theory.ingestion;

import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedChunk;
import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedVariation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@code theory/<slug>.yaml} file into a {@link ParsedOpening}. The
 * chunker is deliberately generic: every scalar field that isn't structural
 * becomes a chunk whose {@code section} is the YAML key, so adding a new field
 * to the corpus (e.g. {@code endgame_notes}) needs no code change here.
 */
@Component
public class TheoryCorpusParser {

    private static final Set<String> OPENING_STRUCTURAL_KEYS =
            Set.of("opening", "slug", "main_line", "variations");
    private static final Set<String> VARIATION_STRUCTURAL_KEYS =
            Set.of("name", "identifying_moves", "sub_variations");

    private final ObjectMapper yaml = new YAMLMapper();

    public ParsedOpening parse(Resource resource) {
        String filename = resource.getFilename();
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = yaml.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read theory file " + filename, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(filename + " is not a YAML mapping");
        }

        String openingName = scalarText(root.get("opening"));
        if (openingName == null) {
            throw new IllegalStateException(filename + " has no 'opening' field");
        }

        List<ParsedVariation> variations = new ArrayList<>();
        List<ParsedChunk> chunks = new ArrayList<>();

        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (OPENING_STRUCTURAL_KEYS.contains(field.getKey())) {
                continue;
            }
            String value = scalarText(field.getValue());
            if (value != null) {
                chunks.add(new ParsedChunk(null, field.getKey(), value));
            }
        }

        JsonNode variationsNode = root.get("variations");
        if (variationsNode != null && variationsNode.isArray()) {
            for (JsonNode variation : variationsNode) {
                walkVariation(variation, null, filename, variations, chunks);
            }
        }

        return new ParsedOpening(openingName, scalarText(root.get("slug")),
                "theory/" + filename, variations, chunks);
    }

    private void walkVariation(JsonNode node, List<String> parentMoves, String filename,
                               List<ParsedVariation> variations, List<ParsedChunk> chunks) {
        String name = scalarText(node.get("name"));
        List<String> moves = moveList(node.get("identifying_moves"));
        if (name == null || moves.isEmpty()) {
            throw new IllegalStateException(
                    filename + " has a variation missing 'name' or 'identifying_moves'");
        }
        variations.add(new ParsedVariation(name, moves, parentMoves));

        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (VARIATION_STRUCTURAL_KEYS.contains(field.getKey())) {
                continue;
            }
            String value = scalarText(field.getValue());
            if (value != null) {
                chunks.add(new ParsedChunk(moves, field.getKey(), value));
            }
        }

        JsonNode subs = node.get("sub_variations");
        if (subs != null && subs.isArray()) {
            for (JsonNode sub : subs) {
                walkVariation(sub, moves, filename, variations, chunks);
            }
        }
    }

    private static String scalarText(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static List<String> moveList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> moves = new ArrayList<>(node.size());
        node.forEach(element -> moves.add(element.asText()));
        return moves;
    }
}
