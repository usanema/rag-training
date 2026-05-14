package com.pawer.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record QueryResult(String answer, List<Map<String, Object>> sources) {}

    public QueryResult query(String question) {
        var advisor = buildAdvisor();
        var response = chatClientBuilder.build()
                .prompt()
                .advisors(advisor)
                .user(question)
                .call()
                .chatResponse();

        String answer = response.getResult().getOutput().getText();

        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) response.getMetadata()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        return new QueryResult(answer, buildSources(docs));
    }

    public Flux<String> query_stream(String question) {
        var advisor = buildAdvisor();
        AtomicBoolean sourcesSent = new AtomicBoolean(false);

        return chatClientBuilder.build()
                .prompt()
                .advisors(advisor)
                .user(question)
                .stream()
                .chatResponse()
                .flatMap(response -> {
                    var parts = new java.util.ArrayList<Flux<String>>();

                    if (!sourcesSent.get()) {
                        @SuppressWarnings("unchecked")
                        List<Document> docs = (List<Document>) response.getMetadata()
                                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
                        if (docs != null) {
                            sourcesSent.set(true);
                            try {
                                String json = objectMapper.writeValueAsString(buildSources(docs));
                                parts.add(Flux.just("__SOURCES__:" + json));
                            } catch (Exception ignored) {}
                        }
                    }

                    String text = response.getResult() != null
                            ? response.getResult().getOutput().getText() : null;
                    if (text != null && !text.isEmpty()) {
                        parts.add(Flux.just(text));
                    }

                    return parts.isEmpty() ? Flux.empty() : Flux.concat(parts);
                });
    }

    private QuestionAnswerAdvisor buildAdvisor() {
        var search = ragProperties.search();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build())
                .build();
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
                        "pages",  e.getValue().stream().distinct().sorted().toList()
                ))
                .toList();
    }
}
