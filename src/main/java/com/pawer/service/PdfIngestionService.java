package com.pawer.service;

import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfIngestionService {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public int ingest(Resource pdfResource) {
        log.info("Wczytuję PDF: {}", pdfResource.getFilename());

        // 1. Wczytaj PDF strona po stronie
        List<Document> pages = new PagePdfDocumentReader(
                pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1)
                        .build()
        ).read();

        log.info("Wczytano stron: {}", pages.size());

        // 2. Podziel na chunki
        var splitter = buildSplitter();
        List<Document> chunks = splitter.apply(pages);

        // 3. Dodaj nazwę pliku do metadata każdego chunka
        String fileName = pdfResource.getFilename();
        chunks.forEach(chunk ->
                chunk.getMetadata().put("source", fileName)
        );

        log.info("Powstało chunków: {}", chunks.size());

        // 4. Zaindeksuj w Qdrant
        vectorStore.add(chunks);

        log.info("✅ Zaindeksowano {} chunków z pliku: {}", chunks.size(), fileName);
        return chunks.size();
    }

    // Podgląd chunków bez zapisywania do vector store
    public List<Map<String, Object>> preview(Resource pdfResource) {
        List<Document> pages = new PagePdfDocumentReader(
                pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1)
                        .build()
        ).read();

        List<Document> chunks = buildSplitter().apply(pages);

        return chunks.stream()
                .map(chunk -> Map.<String, Object>of(
                        "index", chunks.indexOf(chunk),
                        "length", chunk.getText().length(),
                        "page", chunk.getMetadata().getOrDefault("page_number", "?"),
                        "preview", chunk.getText().substring(0, Math.min(200, chunk.getText().length()))
                ))
                .toList();
    }

    public void delete(String fileName) {
        vectorStore.delete(
                List.of("source == '" + fileName + "'")
        );
        log.info("🗑️ Usunięto dokumenty z pliku: {}", fileName);
    }

    private TokenTextSplitter buildSplitter() {
        var chunking = ragProperties.chunking();
        return TokenTextSplitter.builder()
                .withChunkSize(chunking.chunkSize())
                .withMinChunkSizeChars(chunking.minChunkSize())
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
    }
}
