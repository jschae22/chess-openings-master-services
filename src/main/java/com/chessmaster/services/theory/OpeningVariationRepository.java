package com.chessmaster.services.theory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpeningVariationRepository extends JpaRepository<OpeningVariation, Long> {

    List<OpeningVariation> findByOpeningId(Long openingId);
}
