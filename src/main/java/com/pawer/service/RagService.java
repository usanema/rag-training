package com.pawer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;
    private final ChatMemory chatMemory;
    private final InMemoryRunner adkRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_ID = "rag-studio-user";

    private static final String LEGACY_SYSTEM_PROMPT = """
            Jesteś precyzyjnym asystentem odpowiadającym na podstawie dostarczonych fragmentów dokumentów
            oraz historii rozmowy.

            Zasady:
            - Jeśli pytanie dotyczy dokumentów — odpowiadaj TYLKO na podstawie dostarczonego kontekstu RAG
            - Jeśli pytanie nawiązuje do poprzednich wiadomości (np. "a co z tym?", "rozwiń punkt 2") — korzystaj z historii rozmowy
            - Jeśli odpowiedź nie wynika ani z kontekstu ani z historii, napisz: "Nie znalazłem odpowiedzi w dostępnych dokumentach."
            - Odpowiadaj w języku pytania użytkownika
            - Formatuj odpowiedzi w Markdown: nagłówki (##), listy (-), pogrubienia (**tekst**), bloki kodu (``` ```)
            - Bądź zwięzły i precyzyjny
            """;

    public record QueryResult(String answer, List<Map<String, Object>> sources) {}

    // ── Public API ──────────────────────────────────────────────────────────

    public QueryResult query(String question, String conversationId) {
        if (ragProperties.routing().enabled()) {
            try {
                return adkQuery(question, conversationId);
            } catch (Exception e) {
                log.warn("ADK routing failed, falling back to legacy pipeline: {}", e.getMessage());
            }
        }
        return legacyQuery(question, conversationId);
    }

    public Flux<String> query_stream(String question, String conversationId) {
        if (ragProperties.routing().enabled()) {
            return adkStream(question, conversationId)
                    .onErrorResume(e -> {
                        log.warn("ADK routing failed, falling back to legacy pipeline: {}", e.getMessage());
                        return legacyStream(question, conversationId);
                    });
        }
        return legacyStream(question, conversationId);
    }

    // ── ADK pipeline ────────────────────────────────────────────────────────

    private QueryResult adkQuery(String question, String conversationId) {
        Content userContent = Content.fromParts(Part.fromText(question));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        List<Event> events = adkRunner
                .runAsync(USER_ID, conversationId, userContent, runConfig)
                .toList()
                .blockingGet();

        String answer = events.stream()
                .filter(Event::finalResponse)
                .findFirst()
                .flatMap(Event::content)
                .map(Content::text)
                .orElse("");

        List<Map<String, Object>> sources = extractSourcesFromEvents(events);
        return new QueryResult(answer, sources);
    }

    private Flux<String> adkStream(String question, String conversationId) {
        Content userContent = Content.fromParts(Part.fromText(question));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        AtomicBoolean sourcesSent = new AtomicBoolean(false);
        AtomicReference<String> sourcesJsonRef = new AtomicReference<>("[{\"source\":\"(no rag)\",\"pages\":[]}]");

        return Flux.from(adkRunner.runAsync(USER_ID, conversationId, userContent, runConfig))
                .concatMap(event -> {
                    // Capture sources_json when tool response arrives
                    extractSourcesJsonFromEvent(event).ifPresent(sourcesJsonRef::set);

                    // Extract text token (Content.text() concatenates all text parts)
                    String token = event.content().map(Content::text).orElse("");
                    if (token.isEmpty()) return Flux.empty();

                    // Emit __SOURCES__ before the very first text token
                    if (sourcesSent.compareAndSet(false, true)) {
                        return Flux.just("__SOURCES__:" + sourcesJsonRef.get(), token);
                    }
                    return Flux.just(token);
                })
                .concatWith(Flux.defer(() -> {
                    if (sourcesSent.compareAndSet(false, true)) {
                        return Flux.just("__SOURCES__:" + sourcesJsonRef.get());
                    }
                    return Flux.empty();
                }));
    }

    // ── Event helpers ────────────────────────────────────────────────────────

    private Optional<String> extractSourcesJsonFromEvent(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .stream()
                .flatMap(Collection::stream)
                .flatMap(p -> p.functionResponse().stream())
                .filter(fr -> "rag_search".equals(fr.name().orElse("")))
                .findFirst()
                .flatMap(FunctionResponse::response)
                .map(r -> String.valueOf(r.getOrDefault("sources_json", "[]")));
    }

    private List<Map<String, Object>> extractSourcesFromEvents(List<Event> events) {
        return events.stream()
                .flatMap(e -> e.content().flatMap(Content::parts).stream().flatMap(Collection::stream))
                .flatMap(p -> p.functionResponse().stream())
                .filter(fr -> "rag_search".equals(fr.name().orElse("")))
                .findFirst()
                .flatMap(FunctionResponse::response)
                .map(r -> {
                    try {
                        String json = String.valueOf(r.getOrDefault("sources_json", "[]"));
                        return objectMapper.<List<Map<String, Object>>>readValue(json,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                    } catch (Exception e) {
                        return List.<Map<String, Object>>of();
                    }
                })
                .orElse(List.of());
    }

    // ── Legacy pipeline (fallback / routing disabled) ────────────────────────

    private QueryResult legacyQuery(String question, String conversationId) {
        var response = chatClientBuilder.build()
                .prompt()
                .system(LEGACY_SYSTEM_PROMPT)
                .advisors(a -> a.param(CONVERSATION_ID_KEY, conversationId))
                .advisors(buildMemoryAdvisor(), buildRagAdvisor())
                .user(question)
                .call()
                .chatResponse();

        String answer = response.getResult().getOutput().getText();

        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) response.getMetadata()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        return new QueryResult(answer, buildSources(docs));
    }

    private Flux<String> legacyStream(String question, String conversationId) {
        // W Spring AI 2.0.1 streaming advisor nie przenosi qa_retrieved_documents
        // do ChatClientResponse — pobieramy dokumenty bezpośrednio przed streamem.
        var search = ragProperties.search();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build());

        Flux<String> sourcesFlux = Flux.defer(() -> {
            try {
                String json = objectMapper.writeValueAsString(buildSources(docs));
                return Flux.just("__SOURCES__:" + json);
            } catch (Exception ignored) {
                return Flux.empty();
            }
        });

        Flux<String> textFlux = chatClientBuilder.build()
                .prompt()
                .system(LEGACY_SYSTEM_PROMPT)
                .advisors(a -> a.param(CONVERSATION_ID_KEY, conversationId))
                .advisors(buildMemoryAdvisor(), buildRagAdvisor())
                .user(question)
                .stream()
                .chatResponse()
                .mapNotNull(response -> response.getResult() != null
                        ? response.getResult().getOutput().getText() : null)
                .filter(text -> !text.isEmpty());

        return Flux.concat(sourcesFlux, textFlux);
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    // conversationId izoluje historię per wątek chatu. W Spring AI 2.0.1 ID przekazywany
    // przez param "chat_memory_conversation_id" — advisor odczytuje go z kontekstu żądania.
    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private MessageChatMemoryAdvisor buildMemoryAdvisor() {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    private QuestionAnswerAdvisor buildRagAdvisor() {
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
                        "pages", e.getValue().stream().distinct().sorted().toList()
                ))
                .toList();
    }

    // ── Other endpoints ──────────────────────────────────────────────────────

    // Zwraca unikalne nazwy plików zaindeksowanych w aktywnym vector store.
    // Używa szerokiego similarity search z niskim progiem zamiast natywnego listowania,
    // żeby działać jednolicie z oboma storami (Redis i Qdrant).
    public List<String> listDocuments() {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("dokument")
                        .topK(1000)
                        .similarityThreshold(0.0)
                        .build());
        return docs.stream()
                .map(d -> d.getMetadata().getOrDefault("source", "unknown").toString())
                .filter(s -> !s.equals("unknown"))
                .distinct()
                .sorted()
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
