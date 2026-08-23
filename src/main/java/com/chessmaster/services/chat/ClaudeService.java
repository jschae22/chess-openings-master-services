package com.chessmaster.services.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class ClaudeService {

    private static final String SYSTEM_PROMPT = """
            You are a Grandmaster chess coach helping a student learn chess openings.
            Respond as a knowledgeable, encouraging chess coach. Keep your response concise
            and focused. Use Markdown for formatting.""";

    private static final long MAX_TOKENS = 1024L;

    private final AnthropicClient client;
    private final String model;

    public ClaudeService(@Value("${claude.api-key}") String apiKey,
                          @Value("${claude.model:claude-haiku-4-5}") String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    public String chat(ChatRequest request) {
        String moves = (request.moveHistory() == null || request.moveHistory().isEmpty())
                ? "No moves played yet"
                : String.join(", ", request.moveHistory());

        String userMessage = """
                The student is studying: "%s".
                Move history so far: %s.
                Current board position (FEN): %s.

                Student's question: %s"""
                .formatted(
                        request.openingName() != null ? request.openingName() : "an unknown opening",
                        moves,
                        request.currentFen() != null ? request.currentFen() : "unknown",
                        request.userMessage()
                );

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            throw new ChatException("Claude API request failed", e);
        }

        String reply = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        if (reply.isBlank()) {
            throw new ChatException("Empty response from Claude API");
        }

        return reply;
    }
}
