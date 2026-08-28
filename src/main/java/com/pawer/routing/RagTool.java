package com.pawer.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RagTool {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Annotations.Schema(
            name = "rag_search",
            description = "Search the document knowledge base for relevant content. " +
                          "Call this when the user's question is about specific documents, " +
                          "contracts, reports, or content that might be indexed in the knowledge base. " +
                          "Do NOT call for general knowledge, math, or programming concept questions.")
    public Map<String, Object> ragSearch(
            @Annotations.Schema(name = "query", description = "Search query derived from the user's question")
            String query
    ) {
        var search = ragProperties.search();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build());

        String context = docs.stream()
                .map(doc -> "Source: " + doc.getMetadata().getOrDefault("source", "?") +
                            " (page " + doc.getMetadata().getOrDefault("page_number", "?") + ")\n" +
                            doc.getText())
                .collect(Collectors.joining("\n\n---\n\n"));

        String sourcesJson;
        try {
            sourcesJson = objectMapper.writeValueAsString(buildSources(docs));
        } catch (Exception e) {
            sourcesJson = "[]";
        }

        return Map.of(
                "context", context.isEmpty() ? "No relevant documents found in the knowledge base." : context,
                "sources_json", sourcesJson
        );
    }

    public FunctionTool toFunctionTool() {
        return FunctionTool.create(this, "ragSearch");
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
