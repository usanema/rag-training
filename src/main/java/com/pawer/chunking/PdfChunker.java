package com.pawer.chunking;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * Interfejs strategii chunkowania PDF-ów.
 * Każda implementacja odpowiada jednemu ChunkingStrategy enum.
 */
public interface PdfChunker {

    ChunkingStrategy strategy();

    /**
     * Wczytuje PDF z zasobu i zwraca podzielone fragmenty.
     */
    ChunkingResult chunk(Resource pdfResource);

    /**
     * Domyślnie dodaje metadata "source" do każdego chunka.
     */
    default void enrichMetadata(List<Document> chunks, String fileName) {
        chunks.forEach(c -> c.getMetadata().put("source", fileName));
    }
}