package com.pawer.service;

import com.pawer.chunking.ChunkerFactory;
import com.pawer.chunking.ChunkingResult;
import com.pawer.chunking.ChunkingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfIngestionService {

    private final VectorStore vectorStore;
    private final ChunkerFactory chunkerFactory;
    private final DocumentCatalogService documentCatalogService;

    // parentId -> Document — przechowywane w pamięci dla strategii HIERARCHICAL
    private final Map<String, Document> parentStore = new ConcurrentHashMap<>();

    // ── Ingest ───────────────────────────────────────────────────────────────

    public IngestResult ingest(Resource pdfResource, ChunkingStrategy strategy) {
        log.info("Ingesting '{}' | strategia: {}", pdfResource.getFilename(), strategy);
        long start = System.currentTimeMillis();

        long t1 = System.currentTimeMillis();
        ChunkingResult result = chunkerFactory.get(strategy).chunk(pdfResource);
        long chunkingMs = System.currentTimeMillis() - t1;

        // Dla HIERARCHICAL — zapisz parent chunki do lokalnego store
        if (result.hasParents()) {
            parentStore.putAll(result.parentChunks());
            log.info("Zapisano {} parent chunków do pamięci", result.parentChunks().size());
        }

        long t2 = System.currentTimeMillis();
        vectorStore.add(result.chunks());
        long embeddingMs = System.currentTimeMillis() - t2;

        documentCatalogService.generateAndStore(pdfResource.getFilename(), result.chunks());

        long totalMs = System.currentTimeMillis() - start;

        log.info("✅ Zaindeksowano {} chunków | chunking: {}ms | embedding+add: {}ms | total: {}ms",
                result.chunks().size(), chunkingMs, embeddingMs, totalMs);

        return new IngestResult(
                pdfResource.getFilename(),
                strategy.name(),
                result.chunks().size(),
                chunkingMs,
                embeddingMs,
                totalMs
        );
    }

    // ── Preview (bez zapisu do Qdrant) ───────────────────────────────────────

    public List<Map<String, Object>> preview(Resource pdfResource, ChunkingStrategy strategy) {
        ChunkingResult result = chunkerFactory.get(strategy).chunk(pdfResource);
        List<Document> chunks = result.chunks();

        return chunks.stream()
                .map(c -> Map.<String, Object>of(
                        "index",    chunks.indexOf(c),
                        "length",   c.getText().length(),
                        "page",     c.getMetadata().getOrDefault("page_number", "?"),
                        "type",     c.getMetadata().getOrDefault("chunk_type", strategy.name().toLowerCase()),
                        "parentId", c.getMetadata().getOrDefault("parentId", ""),
                        "preview",  c.getText().substring(0, Math.min(300, c.getText().length())),
                        "text",     c.getText()
                ))
                .toList();
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(String fileName) {
        vectorStore.delete(List.of("source == '" + fileName + "'"));
        documentCatalogService.remove(fileName);
        log.info("🗑️ Usunięto dokumenty z pliku: {}", fileName);
    }

    // ── Parent lookup (dla HIERARCHICAL) ─────────────────────────────────────

    public Document getParent(String parentId) {
        return parentStore.get(parentId);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record IngestResult(
            String file,
            String strategy,
            int chunks,
            long chunkingMs,
            long embeddingMs,
            long totalMs
    ) {}
}