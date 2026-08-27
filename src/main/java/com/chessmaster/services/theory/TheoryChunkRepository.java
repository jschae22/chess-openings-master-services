package com.chessmaster.services.theory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheoryChunkRepository extends JpaRepository<TheoryChunk, Long> {

    List<TheoryChunk> findByOpeningId(Long openingId);
}
