package com.pawer.chunking.strategy;

import com.pawer.chunking.ChunkingResult;
import com.pawer.chunking.ChunkingStrategy;
import com.pawer.chunking.PdfChunker;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dzieli PDF podążając za strukturą nagłówków/akapitów (TOC).
 * Każda sekcja między nagłówkami = jeden chunk.
 * Jeśli sekcja jest zbyt długa — dodatkowo dzielona TokenTextSplitterem.
 *
 * Wymaga: PDF z tabelą treści (TOC / outline).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParagraphChunker implements PdfChunker {

    private final RagProperties ragProperties;

    @Override
    public ChunkingStrategy strategy() {
        return ChunkingStrategy.PARAGRAPH;
    }

    @Override
    public ChunkingResult chunk(Resource pdfResource) {
        List<Document> paragraphs;

        try {
            paragraphs = new ParagraphPdfDocumentReader(
                    pdfResource,
                    PdfDocumentReaderConfig.builder()
                            .withPageTopMargin(72)
                            .withPageBottomMargin(72)
                            .build()
            ).read();
        } catch (Exception e) {
            log.warn("[PARAGRAPH] PDF nie ma TOC, fallback na PAGE reader: {}", e.getMessage());
            // Fallback — czytaj strona po stronie jeśli brak TOC
            return new TokenChunker(ragProperties).chunk(pdfResource);
        }

        log.debug("[PARAGRAPH] Wykryto sekcji: {}", paragraphs.size());

        // Podziel zbyt długie sekcje
        var cfg = ragProperties.chunking();
        var splitter = buildSplitter();

        List<Document> chunks = paragraphs.stream()
                .flatMap(p -> {
                    if (p.getText().length() > cfg.chunkSize() * 4) {
                        return splitter.apply(List.of(p)).stream();
                    }
                    return java.util.stream.Stream.of(p);
                })
                .toList();

        enrichMetadata(chunks, pdfResource.getFilename());
        // Zachowaj tytuł sekcji z metadata (dodany przez ParagraphPdfDocumentReader)
        paragraphs.forEach(p ->
                chunks.stream()
                        .filter(c -> c.getText().startsWith(p.getText().substring(0, Math.min(50, p.getText().length()))))
                        .forEach(c -> c.getMetadata().put("section_title",
                                p.getMetadata().getOrDefault("title", "")))
        );

        log.info("[PARAGRAPH] {} sekcji → {} chunków", paragraphs.size(), chunks.size());
        return ChunkingResult.of(chunks);
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