package com.chessmaster.services.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.chessmaster.services.opening.Opening;
import com.chessmaster.services.opening.OpeningRepository;
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
            named variations, or statistics for a line you are not sure about.

            The theory notes are the app's own reference material, not something the student
            wrote: call them "our theory notes", never "your notes". Give advice from the
            perspective of the side the student plays, which the message states. Answer the
            student's question directly; only end with a follow-up question when the message
            is not a direct question or a natural next step is worth offering.""";

    private static final long MAX_TOKENS = 1024L;

    private static final String NO_NOTES_MESSAGE = "No theory notes are available for this question."
            + " Answer from general knowledge, and tell the student you could not check it against"
            + " our theory notes.\n";

    private final AnthropicClient client;
    private final String model;
    private final TheoryRetrievalService retrieval;
    private final OpeningRepository openings;

    public ClaudeService(@Value("${claude.api-key}") String apiKey,
                          @Value("${claude.model:claude-haiku-4-5}") String model,
                          TheoryRetrievalService retrieval,
                          OpeningRepository openings) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build();
        this.model = model;
        this.retrieval = retrieval;
        this.openings = openings;
    }

    public ChatResult chat(ChatRequest request) {
        RetrievedContext context = retrieval.retrieve(
                request.openingName(), request.moveHistory(), request.userMessage());

        String moves = (request.moveHistory() == null || request.moveHistory().isEmpty())
                ? "No moves played yet"
                : String.join(", ", request.moveHistory());

        String userMessage = """
                The student is studying: "%s". %s
                Move history so far: %s.
                Current board position (FEN): %s.

                %s
                Student's question: %s"""
                .formatted(
                        request.openingName() != null ? request.openingName() : "an unknown opening",
                        sideLine(studentSide(request.openingName())),
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
        } catch (AnthropicException | IllegalArgumentException e) {
            // IllegalArgumentException: the HTTP client rejects malformed config (e.g. a bad header value)
            // before any request is sent; surface it as a 502 rather than letting Spring turn it into a 403.
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

    private Opening.Side studentSide(String openingName) {
        if (openingName == null) {
            return null;
        }
        return openings.findByName(openingName).map(Opening::getSide).orElse(null);
    }

    /** Tells the coach which colour the student plays; empty when the opening (and so the side) is unknown. */
    static String sideLine(Opening.Side side) {
        if (side == null) {
            return "";
        }
        String colour = side == Opening.Side.WHITE ? "White" : "Black";
        return "The student plays %s in this opening, so advise from %s's perspective.".formatted(colour, colour);
    }

    /** Context block for the user message; the wording depends on how well retrieval grounded it. */
    static String theorySection(RetrievedContext context) {
        String notes = context.chunks().stream()
                .map(c -> "---\n" + c.content())
                .collect(Collectors.joining("\n", "", "\n---\n"));
        // Partial grounding with nothing retrieved is the same as having no notes.
        if (context.chunks().isEmpty() && context.status() == RetrievedContext.Status.PARTIAL) {
            return NO_NOTES_MESSAGE;
        }
        return switch (context.status()) {
            case GROUNDED -> "Theory notes (matched line: %s):\n%s".formatted(context.variationName(), notes);
            case PARTIAL -> ("Theory notes (our notes do not cover the student's exact line, so these are"
                    + " only loosely related; use them only where they clearly apply, and say which parts"
                    + " come from general knowledge):\n%s").formatted(notes);
            case UNKNOWN_OPENING -> NO_NOTES_MESSAGE;
            case ERROR -> "Theory notes could not be loaded. Answer from general knowledge, and tell the"
                    + " student you could not check it against our theory notes.\n";
        };
    }
}
