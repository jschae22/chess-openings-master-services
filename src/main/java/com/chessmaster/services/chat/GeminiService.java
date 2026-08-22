package com.chessmaster.services.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class GeminiService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final RestClient restClient;
    private final String apiKey;

    public GeminiService(@Value("${gemini.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    public String chat(ChatRequest request) {
        String moves = (request.moveHistory() == null || request.moveHistory().isEmpty())
                ? "No moves played yet"
                : String.join(", ", request.moveHistory());

        String prompt = """
                You are a Grandmaster chess coach helping a student learn chess openings.
                The student is studying: "%s".
                Move history so far: %s.
                Current board position (FEN): %s.

                Student's question: %s

                Respond as a knowledgeable, encouraging chess coach. Keep your response concise and focused. Use Markdown for formatting."""
                .formatted(
                        request.openingName() != null ? request.openingName() : "an unknown opening",
                        moves,
                        request.currentFen() != null ? request.currentFen() : "unknown",
                        request.userMessage()
                );

        GeminiApiRequest body = new GeminiApiRequest(
                List.of(new Content(List.of(new Part(prompt))))
        );

        GeminiApiResponse response = restClient.post()
                .uri(GEMINI_URL + "?key=" + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(GeminiApiResponse.class);

        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()
                || response.candidates().get(0).content() == null
                || response.candidates().get(0).content().parts() == null
                || response.candidates().get(0).content().parts().isEmpty()) {
            throw new GeminiException("Empty response from Gemini API");
        }

        return response.candidates().get(0).content().parts().get(0).text();
    }

    // Gemini REST API request/response records

    record GeminiApiRequest(List<Content> contents) {}
    record Content(List<Part> parts) {}
    record Part(String text) {}

    record GeminiApiResponse(List<Candidate> candidates) {}
    record Candidate(Content content) {}
}
