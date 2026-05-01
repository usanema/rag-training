package com.pawer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Chunking chunking,
        Search search
) {
    public record Chunking(
            int chunkSize,
            int minChunkSize,
            int chunkOverlap
    ) {}

    public record Search(
            int topK,
            double similarityThreshold
    ) {}
}
