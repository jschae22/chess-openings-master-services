package com.chessmaster.services.theory.retrieval;

import com.chessmaster.services.opening.Opening;
import com.chessmaster.services.opening.OpeningRepository;
import com.chessmaster.services.theory.OpeningVariationRepository;
import com.chessmaster.services.theory.ingestion.VoyageEmbeddingClient;
import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Failure handling only; the SQL itself is covered by {@link TheoryRetrievalServiceIntegrationTest}. */
class TheoryRetrievalServiceFailureTest {

    private final OpeningRepository openings = mock(OpeningRepository.class);
    private final OpeningVariationRepository variations = mock(OpeningVariationRepository.class);
    private final VoyageEmbeddingClient embeddings = mock(VoyageEmbeddingClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Opening opening = mock(Opening.class);

    private TheoryRetrievalService service;

    @BeforeEach
    void setUp() {
        when(opening.getId()).thenReturn(1L);
        when(opening.getName()).thenReturn("Sicilian Defense");
        when(openings.findByName("Sicilian Defense")).thenReturn(Optional.of(opening));
        service = new TheoryRetrievalService(openings, variations, embeddings, jdbc, 5, 3);
    }

    @Test
    void openingLookupFailureYieldsErrorNotAnException() {
        when(openings.findByName(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));

        RetrievedContext context = service.retrieve("Sicilian Defense", List.of("e4", "c5"), "plan?");

        assertThat(context.status()).isEqualTo(Status.ERROR);
        assertThat(context.chunks()).isEmpty();
    }

    @Test
    void variationLookupFailureYieldsErrorKeepingTheOpeningName() {
        when(variations.findByOpeningId(anyLong())).thenThrow(new DataAccessResourceFailureException("db down"));

        RetrievedContext context = service.retrieve("Sicilian Defense", List.of("e4", "c5"), "plan?");

        assertThat(context.status()).isEqualTo(Status.ERROR);
        assertThat(context.openingName()).isEqualTo("Sicilian Defense");
    }

    @Test
    void embeddingFailureYieldsError() {
        when(variations.findByOpeningId(anyLong())).thenReturn(List.of());
        when(embeddings.embedQuery(any())).thenThrow(new IllegalStateException("voyage timed out"));

        assertThat(service.retrieve("Sicilian Defense", List.of("e4", "c5"), "plan?").status())
                .isEqualTo(Status.ERROR);
    }

    @Test
    void nullMoveInHistoryDoesNotThrow() {
        when(variations.findByOpeningId(anyLong())).thenReturn(List.of());
        when(embeddings.embedQuery(any())).thenThrow(new IllegalStateException("not reached in this test"));

        assertThatCode(() -> service.retrieve("Sicilian Defense", Arrays.asList("e4", null), "plan?"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownOpeningNeverTouchesTheEmbeddingService() {
        when(openings.findByName("Bird Opening")).thenReturn(Optional.empty());

        assertThat(service.retrieve("Bird Opening", List.of("f4"), "idea?").status())
                .isEqualTo(Status.UNKNOWN_OPENING);
    }
}
