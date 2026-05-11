package com.pawer.chunking;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * Wynik chunkowania.
 * - chunks       → fragmenty do zaindeksowania w Qdrant
 * - parentChunks → opcjonalnie (tylko HIERARCHICAL) — większe fragmenty
 *                  zwracane LLM-owi jako kontekst; identyfikowane przez
 *                  metadata["parentId"] w chunks
 */
public record ChunkingResult(
        List<Document> chunks,
        Map<String, Document> parentChunks   // parentId -> Document, puste jeśli nie HIERARCHICAL
) {
    public static ChunkingResult of(List<Document> chunks) {
        return new ChunkingResult(chunks, Map.of());
    }

    public static ChunkingResult ofHierarchical(List<Document> chunks,
                                                 Map<String, Document> parents) {
        return new ChunkingResult(chunks, parents);
    }

    public boolean hasParents() {
        return !parentChunks.isEmpty();
    }
}