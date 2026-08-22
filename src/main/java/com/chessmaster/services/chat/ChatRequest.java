package com.chessmaster.services.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @Size(max = 100) String openingName,
        @Size(max = 100, message = "Move history must not exceed 100 moves") List<String> moveHistory,
        @Size(max = 200) String currentFen,
        @NotBlank @Size(max = 1000, message = "Message must not exceed 1000 characters") String userMessage
) {}
