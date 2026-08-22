package com.chessmaster.services.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GeminiService geminiService;
    private final RateLimiterService rateLimiterService;

    public ChatController(GeminiService geminiService, RateLimiterService rateLimiterService) {
        this.geminiService = geminiService;
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest request,
                                  HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);

        if (!rateLimiterService.tryConsume(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Rate limit exceeded. Maximum 20 requests per hour.");
        }

        try {
            String reply = geminiService.chat(request);
            return ResponseEntity.ok(new ChatResponse(reply));
        } catch (GeminiException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("AI coach is unavailable. Please try again later.");
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        // Cloud Run sits behind Google's load balancer; real IP is in X-Forwarded-For
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
