package com.chessmaster.services.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.chessmaster.services.theory.retrieval.RetrievedContext;
import com.chessmaster.services.theory.retrieval.TheoryRetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class ClaudeService {

    private static final String SYSTEM_PROMPT = """
            You are a Grandmaster chess coach helping a student learn chess openings.
            Respond as a knowledgeable, encouraging chess coach. Keep your response concise
            and focused. Use Markdown for formatting.

            When the student's message includes a "Theory notes" section, treat it as your
            source of truth: answer from it, and do not contradict it. If the notes do not
            cover what the student asks, say so plainly instead of guessing, and clearly
            separate anything you add from general knowledge. Never invent move sequences,
            named variations, or statistics for a line you are not sure about.""";

    private static final long MAX_TOKENS = 1024L;

    private final AnthropicClient client;
    private final String model;
    private final TheoryRetrievalService retrieval;

    public ClaudeService(@Value("${claude.api-key}") String apiKey,
                          @Value("${claude.model:claude-haiku-4-5}") String model,
                          TheoryRetrievalService retrieval) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
        this.retrieval = retrieval;
    }

    public ChatResult chat(ChatRequest request) {
        RetrievedContext context = retrieval.retrieve(
                request.openingName(), request.moveHistory(), request.userMessage());

        String moves = (request.moveHistory() == null || request.moveHistory().isEmpty())
                ? "No moves played yet"
                : String.join(", ", request.moveHistory());

        String userMessage = """
                The student is studying: "%s".
                Move history so far: %s.
                Current board position (FEN): %s.

                %s
                Student's question: %s"""
                .formatted(
                        request.openingName() != null ? request.openingName() : "an unknown opening",
                        moves,
                        request.currentFen() != null ? request.currentFen() : "unknown",
                        theorySection(context),
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

        return new ChatResult(reply, context.status());
    }

    /** Context block for the user message; the wording depends on how well retrieval grounded it. */
    static String theorySection(RetrievedContext context) {
        String notes = context.chunks().stream()
                .map(c -> "---\n" + c.content())
                .collect(Collectors.joining("\n", "", "\n---\n"));
        return switch (context.status()) {
            case GROUNDED -> "Theory notes (matched line: %s):\n%s".formatted(context.variationName(), notes);
            case PARTIAL -> ("Theory notes (the student's exact line is not in our notes, so these are only"
                    + " loosely related; say if they don't answer the question):\n%s").formatted(notes);
            case UNKNOWN_OPENING -> "No theory notes are available for this opening. Answer from general"
                    + " knowledge, and tell the student you could not check it against our theory notes.\n";
            case ERROR -> "Theory notes could not be loaded. Answer from general knowledge, and tell the"
                    + " student you could not check it against our theory notes.\n";
        };
    }
}
