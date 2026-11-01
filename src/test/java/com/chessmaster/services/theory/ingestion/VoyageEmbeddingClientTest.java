package com.chessmaster.services.theory.ingestion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The interactive query path must neither throttle nor retry: a chat request cannot wait minutes. */
class VoyageEmbeddingClientTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = (status == 200 ? embeddingJson() : "{\"detail\":\"rate limited\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private VoyageEmbeddingClient client(Duration queryTimeout) {
        return new VoyageEmbeddingClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings",
                "key", "voyage-4-lite", Duration.ofSeconds(60), queryTimeout);
    }

    @Test
    void queriesAreNotThrottledAgainstEachOther() {
        VoyageEmbeddingClient client = client(Duration.ofSeconds(5));
        long start = System.nanoTime();

        client.embedQuery("one");
        client.embedQuery("two");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void rateLimitFailsOnTheFirstAttemptWithoutRetrying() {
        status = 429;
        long start = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).embedQuery("q"))
                .isInstanceOf(RuntimeException.class);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void slowResponseTimesOutInsteadOfHanging() {
        delayMs = 3000;
        long start = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).embedQuery("q"))
                .isInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void apiKeyWithTrailingWhitespaceIsTrimmed() {
        // A secret saved from a Windows shell ends in \r\n, which is an illegal header value.
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings",
                "key\r\n", "voyage-4-lite", Duration.ofSeconds(60), Duration.ofSeconds(5));

        assertThat(client.embedQuery("q")).hasSize(VoyageEmbeddingClient.EMBEDDING_DIMENSION);
    }

    @Test
    void parsesTheEmbedding() {
        float[] vector = client(Duration.ofSeconds(5)).embedQuery("q");

        assertThat(vector).hasSize(VoyageEmbeddingClient.EMBEDDING_DIMENSION);
        assertThat(vector[0]).isEqualTo(0.5f);
    }

    private static String embeddingJson() {
        StringBuilder sb = new StringBuilder("{\"data\":[{\"index\":0,\"embedding\":[");
        for (int i = 0; i < VoyageEmbeddingClient.EMBEDDING_DIMENSION; i++) {
            sb.append(i == 0 ? "0.5" : ",0.0");
        }
        return sb.append("]}]}").toString();
    }
}
