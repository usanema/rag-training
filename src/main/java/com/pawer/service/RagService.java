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

    private static final String SYSTEM_PROMPT = """
            Jesteś precyzyjnym asystentem odpowiadającym wyłącznie na podstawie dostarczonych fragmentów dokumentów.

            Zasady:
            - Odpowiadaj TYLKO na podstawie dostarczonego kontekstu — nie używaj własnej wiedzy
            - Jeśli odpowiedź nie wynika z kontekstu, napisz dosłownie: "Nie znalazłem odpowiedzi w dostępnych dokumentach."
            - Odpowiadaj w języku pytania użytkownika
            - Formatuj każdą odpowiedź w Markdown: używaj nagłówków (##), list (-), pogrubień (**tekst**) i bloków kodu (``` ```) tam gdzie to stosowne
            - Bądź zwięzły i precyzyjny — nie powtarzaj pytania, nie dodawaj zbędnych wstępów
            """;

    public record QueryResult(String answer, List<Map<String, Object>> sources) {}

    public QueryResult query(String question) {
        var advisor = buildAdvisor();
        var response = chatClientBuilder.build()
                .prompt()
                .system(SYSTEM_PROMPT)
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
                .system(SYSTEM_PROMPT)
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
    public static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
