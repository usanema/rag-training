package com.pawer.chunking.strategy;

import com.pawer.chunking.ChunkingResult;
import com.pawer.chunking.ChunkingStrategy;
import com.pawer.chunking.PdfChunker;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dzieli PDF na chunki o stałej liczbie tokenów z nakładką (overlap).
 * Najprostsze i najszybsze podejście.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenChunker implements PdfChunker {

    private final RagProperties ragProperties;

    @Override
    public ChunkingStrategy strategy() {
        return ChunkingStrategy.TOKEN;
    }

    @Override
    public ChunkingResult chunk(Resource pdfResource) {
        var cfg = ragProperties.chunking();

        List<Document> pages = new PagePdfDocumentReader(
                pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1)
                        .build()
        ).read();

        log.debug("[TOKEN] Wczytano stron: {}", pages.size());

        var splitter = buildSplitter(ragProperties.chunking());

        List<Document> chunks = splitter.apply(pages);
        enrichMetadata(chunks, pdfResource.getFilename());

        List<Document> normalized = chunks.stream()
                .map(c -> new Document(c.getText().trim().replaceAll("\\s+", " "), c.getMetadata()))
                .toList();

        log.info("[TOKEN] {} stron → {} chunków (rozmiar: {} tokenów, overlap: {})",
                pages.size(), normalized.size(), cfg.chunkSize(), cfg.chunkOverlap());

        return ChunkingResult.of(normalized);
    }

    public static TokenTextSplitter buildSplitter(RagProperties.Chunking chunking) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunking.chunkSize())
                .withMinChunkSizeChars(chunking.minChunkSize())
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
    }
}