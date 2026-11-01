package com.chessmaster.services.chat;

import com.chessmaster.services.theory.retrieval.RetrievedContext.Status;

/** @param grounding how well the reply is backed by the theory corpus, so clients can flag weak answers */
public record ChatResponse(String reply, Status grounding) {}
