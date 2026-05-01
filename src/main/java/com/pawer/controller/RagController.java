package com.pawer.controller;

import com.pawer.service.PdfIngestionService;
import com.pawer.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagController {

    private final PdfIngestionService ingestionService;
    private final RagService ragService;

    // ── Zaindeksuj PDF ──────────────────────────────────────────────────────
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Plik jest pusty"));
        }
        if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Tylko pliki PDF"));
        }

        int chunks = ingestionService.ingest(file.getResource());
        return ResponseEntity.ok(Map.of(
                "file",   file.getOriginalFilename(),
                "chunks", chunks,
                "status", "zaindeksowano"
        ));
    }

    // ── Podgląd chunków (bez zapisu) ────────────────────────────────────────
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<Map<String, Object>>> preview(
            @RequestParam("file") MultipartFile file) {

        List<Map<String, Object>> chunks = ingestionService.preview(file.getResource());
        return ResponseEntity.ok(chunks);
    }

    // ── Zapytaj ─────────────────────────────────────────────────────────────
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam String q) {

        String answer = ragService.query(q);
        return ResponseEntity.ok(Map.of(
                "question", q,
                "answer",   answer
        ));
    }

    // ── Usuń dokumenty po nazwie pliku ──────────────────────────────────────
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestParam String fileName) {

        ingestionService.delete(fileName);
        return ResponseEntity.ok(Map.of(
                "file",   fileName,
                "status", "usunięto"
        ));
    }
}
