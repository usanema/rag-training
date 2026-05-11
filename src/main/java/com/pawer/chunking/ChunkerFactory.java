package com.pawer.chunking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fabryka strategii chunkowania.
 * Spring automatycznie wstrzykuje wszystkie implementacje PdfChunker.
 */
@Component
@RequiredArgsConstructor
public class ChunkerFactory {

    private final List<PdfChunker> chunkers;

    private Map<ChunkingStrategy, PdfChunker> cache;

    public PdfChunker get(ChunkingStrategy strategy) {
        if (cache == null) {
            cache = chunkers.stream()
                    .collect(Collectors.toMap(PdfChunker::strategy, Function.identity()));
        }
        PdfChunker chunker = cache.get(strategy);
        if (chunker == null) {
            throw new IllegalArgumentException("Brak implementacji dla strategii: " + strategy);
        }
        return chunker;
    }

    public List<ChunkingStrategy> availableStrategies() {
        return chunkers.stream().map(PdfChunker::strategy).toList();
    }
}