package com.chessmaster.services.theory.ingestion;

import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedChunk;
import com.chessmaster.services.theory.ingestion.ParsedOpening.ParsedVariation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TheoryCorpusParserTest {

    private final TheoryCorpusParser parser = new TheoryCorpusParser();

    @Test
    void everyCorpusFileParses() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:theory/*.yaml");

        assertThat(files).hasSizeGreaterThanOrEqualTo(9);
        for (Resource file : files) {
            ParsedOpening parsed = parser.parse(file);
            assertThat(parsed.openingName()).isNotBlank();
            assertThat(parsed.source()).isEqualTo("theory/" + file.getFilename());
            assertThat(parsed.chunks()).isNotEmpty();
            assertThat(parsed.chunks())
                    .noneMatch(c -> List.of("name", "identifying_moves", "sub_variations",
                            "opening", "slug", "main_line", "variations").contains(c.section()));
        }
    }

    @Test
    void extractsOpeningLevelAndVariationChunks() {
        ParsedOpening parsed = parser.parse(new ClassPathResource("theory/italian-game.yaml"));

        assertThat(parsed.openingName()).isEqualTo("Italian Game");
        assertThat(parsed.slug()).isEqualTo("italian-game");

        assertThat(openingLevelSections(parsed))
                .contains("overview", "why_main_line_moves_are_played");

        assertThat(parsed.chunks())
                .filteredOn(c -> "overview".equals(c.section()) && c.variationIdentifyingMoves() == null)
                .singleElement()
                .satisfies(c -> assertThat(c.text()).contains("classical openings"));
    }

    @Test
    void buildsVariationTreeWithParentPointers() {
        ParsedOpening parsed = parser.parse(new ClassPathResource("theory/italian-game.yaml"));

        ParsedVariation giuocoPiano = variationNamed(parsed, "Giuoco Piano");
        assertThat(giuocoPiano.parentIdentifyingMoves()).isNull();
        assertThat(giuocoPiano.identifyingMoves())
                .containsExactly("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5");

        ParsedVariation evansGambit = variationNamed(parsed, "Evans Gambit");
        assertThat(evansGambit.parentIdentifyingMoves()).isEqualTo(giuocoPiano.identifyingMoves());

        // A parent always appears before its children (pre-order) so ingestion can resolve ids.
        assertThat(indexOf(parsed, "Giuoco Piano")).isLessThan(indexOf(parsed, "Evans Gambit"));
    }

    @Test
    void attachesSubVariationChunksToTheSubVariation() {
        ParsedOpening parsed = parser.parse(new ClassPathResource("theory/italian-game.yaml"));
        ParsedVariation friedLiver = variationNamed(parsed, "Fried Liver Attack");

        assertThat(parsed.chunks())
                .filteredOn(c -> friedLiver.identifyingMoves().equals(c.variationIdentifyingMoves()))
                .extracting(ParsedChunk::section)
                .contains("idea", "traps");
    }

    private static List<String> openingLevelSections(ParsedOpening parsed) {
        return parsed.chunks().stream()
                .filter(c -> c.variationIdentifyingMoves() == null)
                .map(ParsedChunk::section)
                .toList();
    }

    private static ParsedVariation variationNamed(ParsedOpening parsed, String name) {
        return parsed.variations().stream()
                .filter(v -> v.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no variation named " + name));
    }

    private static int indexOf(ParsedOpening parsed, String variationName) {
        for (int i = 0; i < parsed.variations().size(); i++) {
            if (parsed.variations().get(i).name().equals(variationName)) {
                return i;
            }
        }
        throw new AssertionError("no variation named " + variationName);
    }
}
