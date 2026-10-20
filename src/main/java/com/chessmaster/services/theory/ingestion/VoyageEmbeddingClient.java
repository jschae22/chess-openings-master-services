package com.chessmaster.services.theory.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client for Voyage AI's embeddings endpoint.
 *
 * <p>A billing-free Voyage account is capped at 3 requests/minute, so requests
 * are spaced out by {@code voyage.min-request-interval} and retried with backoff
 * on HTTP 429. That makes an ingestion run take a couple of minutes but keeps it
 * within the free tier; set the interval to {@code 0s} once a payment method is
 * added.
 */
@Component
public class VoyageEmbeddingClient {

    /** Matches the {@code vector(1024)} column; voyage-4-lite's native dimension. */
    public static final int EMBEDDING_DIMENSION = 1024;

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);
    private static final String ENDPOINT = "https://api.voyageai.com/v1/embeddings";
    private static final int MAX_INPUTS_PER_REQUEST = 64;
    private static final int MAX_RETRIES = 5;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(25);

    private final RestClient http;
    private final String model;
    private final long minRequestIntervalMs;

    private long lastRequestAt;

    public VoyageEmbeddingClient(@Value("${voyage.api-key}") String apiKey,
                                 @Value("${voyage.model:voyage-4-lite}") String model,
                                 @Value("${voyage.min-request-interval:21s}") Duration minRequestInterval) {
        this.model = model;
        this.minRequestIntervalMs = minRequestInterval.toMillis();
        this.http = RestClient.builder()
                .baseUrl(ENDPOINT)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** Embeds corpus text for storage. Order of the result matches the input. */
    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts, "document");
    }

    /** Embeds a user question for retrieval. */
    public float[] embedQuery(String text) {
        return embed(List.of(text), "query").get(0);
    }

    private List<float[]> embed(List<String> texts, String inputType) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_INPUTS_PER_REQUEST) {
            List<String> batch = texts.subList(start,
                    Math.min(start + MAX_INPUTS_PER_REQUEST, texts.size()));
            result.addAll(embedBatch(batch, inputType));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch, String inputType) {
        Map<String, Object> body = Map.of(
                "input", batch,
                "model", model,
                "input_type", inputType,
                "output_dimension", EMBEDDING_DIMENSION);

        JsonNode response = postWithRetry(body);

        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.size() != batch.size()) {
            throw new IllegalStateException("Voyage returned "
                    + (data == null ? "no data" : data.size() + " embeddings")
                    + " for a batch of " + batch.size());
        }

        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt();
            JsonNode embedding = item.get("embedding");
            if (embedding == null || embedding.size() != EMBEDDING_DIMENSION) {
                throw new IllegalStateException("Voyage embedding has "
                        + (embedding == null ? "no" : embedding.size())
                        + " dimensions, expected " + EMBEDDING_DIMENSION);
            }
            float[] vector = new float[EMBEDDING_DIMENSION];
            for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            ordered[index] = vector;
        }
        return List.of(ordered);
    }

    private JsonNode postWithRetry(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                return http.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                long waitMs = RETRY_BACKOFF.toMillis() * attempt;
                log.warn("Voyage rate-limited (attempt {}/{}), retrying in {}s",
                        attempt, MAX_RETRIES, waitMs / 1000);
                sleep(waitMs);
            }
        }
    }

    private void throttle() {
        long waitMs = minRequestIntervalMs - (System.currentTimeMillis() - lastRequestAt);
        if (waitMs > 0) {
            sleep(waitMs);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on Voyage rate limit", ie);
        }
    }
}
