package com.pawer.controller;

import com.pawer.chunking.ChunkingStrategy;
import com.pawer.service.PdfIngestionService;
import com.pawer.service.RagService;
import com.pawer.service.VectorStoreRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagController {

    private final PdfIngestionService ingestionService;
    private final RagService ragService;
    private final VectorStoreRouter vectorStoreRouter;
    private final EmbeddingModel embeddingModel;

    // ── Vector store — odczyt i przełączanie ────────────────────────────────

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "vectorStore", vectorStoreRouter.getActiveStore()
        ));
    }

    @GetMapping("/store")
    public ResponseEntity<Map<String, Object>> getStore() {
        return ResponseEntity.ok(Map.of(
                "active",    vectorStoreRouter.getActiveStore(),
                "available", VectorStoreRouter.AVAILABLE
        ));
    }

    @PutMapping("/store/{name}")
    public ResponseEntity<Map<String, Object>> switchStore(@PathVariable String name) {
        vectorStoreRouter.switchTo(name);
        return ResponseEntity.ok(Map.of("active", vectorStoreRouter.getActiveStore()));
    }

    // ── Dostępne strategie ──────────────────────────────────────────────────
    @GetMapping("/strategies")
    public List<Map<String, String>> strategies() {
        return Arrays.stream(ChunkingStrategy.values())
                .map(s -> Map.of(
                        "name", s.name(),
                        "description", strategyDescription(s)
                ))
                .toList();
    }

    // ── Zaindeksuj PDF ──────────────────────────────────────────────────────
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "TOKEN") ChunkingStrategy strategy) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plik jest pusty"));
        }
        if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tylko pliki PDF"));
        }

        PdfIngestionService.IngestResult result = ingestionService.ingest(file.getResource(), strategy);
        return ResponseEntity.ok(result);
    }

    // ── Podgląd chunków bez zapisu ──────────────────────────────────────────
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "TOKEN") ChunkingStrategy strategy) {

        return ResponseEntity.ok(ingestionService.preview(file.getResource(), strategy));
    }

    // ── Lista zaindeksowanych dokumentów ───────────────────────────────────
    @GetMapping("/documents")
    public ResponseEntity<List<String>> documents() {
        return ResponseEntity.ok(ragService.listDocuments());
    }

    // ── Zapytaj (zwykły request) ────────────────────────────────────────────
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam String q,
            @RequestParam(defaultValue = "default") String conversationId) {
        var result = ragService.query(q, conversationId);
        return ResponseEntity.ok(Map.of(
                "question", q,
                "answer",   result.answer(),
                "sources",  result.sources()
        ));
    }

    // ── Zapytaj (streaming SSE) ─────────────────────────────────────────────
    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(
            @RequestParam String q,
            @RequestParam(defaultValue = "default") String conversationId) {
        return ragService.query_stream(q, conversationId);
    }

    // ── Usuń dokumenty po nazwie pliku ──────────────────────────────────────
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam String fileName) {
        ingestionService.delete(fileName);
        return ResponseEntity.ok(Map.of("file", fileName, "status", "usunięto"));
    }

    // ── Cosine similarity dwóch tekstów ────────────────────────────────────
    @GetMapping("/similarity")
    public ResponseEntity<Map<String, Object>> similarity(@RequestParam String a, @RequestParam String b) {
        float[] vecA = embeddingModel.embed(a);
        float[] vecB = embeddingModel.embed(b);
        double score = ragService.cosineSimilarity(vecA, vecB);
        return ResponseEntity.ok(Map.of("a", a, "b", b, "score", score));
    }

    // ── Opisy strategii ─────────────────────────────────────────────────────
    private String strategyDescription(ChunkingStrategy s) {
        return switch (s) {
            case TOKEN       -> "Stała liczba tokenów z nakładką. Szybki, ogólny.";
            case PARAGRAPH   -> "Podział po nagłówkach PDF (wymaga TOC). Dobry dla dokumentacji.";
            case SENTENCE    -> "Grupowanie zdań do max rozmiaru. Dobry dla artykułów.";
            case HIERARCHICAL-> "Parent-Child: małe chunki do retrieval, duże jako kontekst LLM.";
            case SEMANTIC    -> "Atomowe twierdzenia przez LLM. Najlepsza jakość, najwolniejszy.";
        };
    }
}