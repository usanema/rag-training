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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Dzieli tekst na zdania (BreakIterator) i grupuje je do max rozmiaru chunka.
 * Zachowuje nakładkę na poziomie zdań (nie tokenów).
 * Dobry do: artykułów, tekstów ciągłych, Q&A.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SentenceChunker implements PdfChunker {

    private final RagProperties ragProperties;

    @Override
    public ChunkingStrategy strategy() {
        return ChunkingStrategy.SENTENCE;
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

        List<Document> chunks = new ArrayList<>();

        for (Document page : pages) {
            String pageNum = (String) page.getMetadata().getOrDefault("page_number", "?");
            List<String> sentences = splitIntoSentences(page.getText());
            List<Document> pageChunks = groupSentences(sentences, cfg.chunkSize(), cfg.chunkOverlap(), pageNum);
            chunks.addAll(pageChunks);
        }

        enrichMetadata(chunks, pdfResource.getFilename());

        log.info("[SENTENCE] {} stron → {} chunków (max {} znaków/chunk)",
                pages.size(), chunks.size(), cfg.chunkSize());

        return ChunkingResult.of(chunks);
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.getDefault());
        iterator.setText(text);

        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private List<Document> groupSentences(List<String> sentences, int maxChars,
                                           int overlapChars, String pageNum) {
        List<Document> chunks = new ArrayList<>();
        if (sentences.isEmpty()) return chunks;

        StringBuilder current = new StringBuilder();
        int overlapStart = 0;

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);

            if (current.length() + sentence.length() > maxChars && current.length() > 0) {
                // Zapisz chunk
                var meta = new HashMap<String, Object>();
                meta.put("page_number", pageNum);
                meta.put("chunk_type", "sentence");
                chunks.add(new Document(current.toString().trim(), meta));

                // Nakładka — cofnij się o overlapChars
                StringBuilder overlap = new StringBuilder();
                for (int j = i - 1; j >= overlapStart && overlap.length() < overlapChars; j--) {
                    overlap.insert(0, sentences.get(j) + " ");
                }
                current = new StringBuilder(overlap);
                overlapStart = i;
            }

            current.append(sentence).append(" ");
        }

        // Ostatni chunk
        if (!current.toString().trim().isEmpty()) {
            var meta = new HashMap<String, Object>();
            meta.put("page_number", pageNum);
            meta.put("chunk_type", "sentence");
            chunks.add(new Document(current.toString().trim(), meta));
        }

        return chunks;
    }
}