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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hierarchiczny podział Parent → Child.
 *
 * Parent (duży chunk ~1500 tokenów) — przechowywany jako kontekst dla LLM.
 * Child  (mały chunk ~300 tokenów)  — indeksowany w Qdrant do retrieval.
 *
 * Każdy child ma w metadata "parentId" wskazujący na odpowiedni parent.
 * Przy odpowiedzi LLM możemy podmienić child na parent dla lepszego kontekstu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HierarchicalChunker implements PdfChunker {

    private final RagProperties ragProperties;

    @Override
    public ChunkingStrategy strategy() {
        return ChunkingStrategy.HIERARCHICAL;
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

        var parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(cfg.chunkSize() * 3)
                .withKeepSeparator(true)
                .withMinChunkSizeChars(cfg.minChunkSize() * 2)
                .withMaxNumChunks(10_000)
                .withMinChunkLengthToEmbed(5)
                .build();

        // Child splitter — małe chunki do embedowania
        var childSplitter = TokenTextSplitter.builder()
                .withChunkSize(cfg.chunkSize() / 2)
                .withKeepSeparator(true)
                .withMinChunkSizeChars(cfg.minChunkSize() / 2)
                .withMaxNumChunks(10_000)
                .withMinChunkLengthToEmbed(5)
                .build();

        List<Document> parents = parentSplitter.apply(pages);
        List<Document> allChildren = new ArrayList<>();
        Map<String, Document> parentMap = new HashMap<>();

        for (Document parent : parents) {
            String parentId = UUID.randomUUID().toString();
            parentMap.put(parentId, parent);

            List<Document> children = childSplitter.apply(List.of(parent));
            children.forEach(child -> {
                child.getMetadata().put("parentId", parentId);
                child.getMetadata().put("page_number",
                        parent.getMetadata().getOrDefault("page_number", "?"));
            });

            allChildren.addAll(children);
        }

        enrichMetadata(allChildren, pdfResource.getFilename());
        enrichMetadata(new ArrayList<>(parentMap.values()), pdfResource.getFilename());

        List<Document> normalizedChildren = allChildren.stream()
                .map(c -> new Document(c.getText().trim().replaceAll("\\s+", " "), c.getMetadata()))
                .toList();

        Map<String, Document> normalizedParents = new HashMap<>();
        parentMap.forEach((id, p) ->
                normalizedParents.put(id, new Document(p.getText().trim().replaceAll("\\s+", " "), p.getMetadata())));

        log.info("[HIERARCHICAL] {} stron → {} parentów → {} child chunków",
                pages.size(), normalizedParents.size(), normalizedChildren.size());

        return ChunkingResult.ofHierarchical(normalizedChildren, normalizedParents);
    }
}