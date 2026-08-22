package com.chessmaster.services.opening;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/openings")
public class OpeningController {

    private final OpeningRepository repository;

    public OpeningController(OpeningRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Opening> getAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Opening> getById(@PathVariable long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
