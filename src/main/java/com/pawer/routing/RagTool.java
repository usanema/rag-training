package com.pawer.routing;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class RagTool {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    // Per-session accumulation of retrieved sources (multi-step retrieval supported)
    private final ConcurrentHashMap<String, List<Map<String, Object>>> sessionSources = new ConcurrentHashMap<>();

    @Annotations.Schema(
            name = "rag_search",
            description = "Search the document knowledge base for relevant content. " +
                          "Call this when the user's question is about specific documents, " +
                          "contracts, reports, or content that might be indexed in the knowledge base. " +
                          "You may call this tool multiple times with different queries to gather complete information. " +
                          "Do NOT call for general knowledge, math, or programming concept questions.")
    public Map<String, Object> ragSearch(
            @Annotations.Schema(name = "query", description = "Search query derived from the user's question")
            String query,
            // ADK injects ToolContext for params named "toolContext"; excluded from function schema
            @Annotations.Schema(name = "toolContext") ToolContext toolContext
    ) {
        var search = ragProperties.search();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build());

        List<Map<String, Object>> sources = buildSources(docs);
        String sessionId = toolContext.sessionId();
        log.debug("rag_search: query='{}', sessionId={}, docs={}", query, sessionId, docs.size());

        // Accumulate sources — supports multi-step retrieval where agent calls tool multiple times
        sessionSources.compute(sessionId, (k, existing) -> {
            if (existing == null) return new ArrayList<>(sources);
            existing.addAll(sources);
            return existing;
        });

        String context = docs.stream()
                .map(doc -> "Source: " + doc.getMetadata().getOrDefault("source", "?") +
                            " (page " + doc.getMetadata().getOrDefault("page_number", "?") + ")\n" +
                            doc.getText())
                .collect(Collectors.joining("\n\n---\n\n"));

        return Map.of(
                "context", context.isEmpty() ? "No relevant documents found in the knowledge base." : context
        );
    }

    public FunctionTool toFunctionTool() {
        return FunctionTool.create(this, "ragSearch");
    }

    public List<Map<String, Object>> pollSources(String sessionId) {
        return sessionSources.remove(sessionId);
    }

    public void clearSources(String sessionId) {
        sessionSources.remove(sessionId);
    }

    private List<Map<String, Object>> buildSources(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return List.of();
        return docs.stream()
                .collect(Collectors.groupingBy(
                        doc -> doc.getMetadata().getOrDefault("source", "unknown").toString(),
                        Collectors.mapping(
                                doc -> doc.getMetadata().getOrDefault("page_number", "?").toString(),
                                Collectors.toList()
                        )
                ))
                .entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "source", e.getKey(),
                        "pages", e.getValue().stream().distinct().sorted().toList()
                ))
                .toList();
    }
}
