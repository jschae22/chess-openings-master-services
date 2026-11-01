package com.chessmaster.services.chat;

import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;

public record ChatResult(String reply, Status grounding) {}
