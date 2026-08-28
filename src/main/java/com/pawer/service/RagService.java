package com.pawer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.pawer.config.RagProperties;
import com.pawer.routing.AdkAgentConfig;
import com.pawer.routing.RagTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;
    private final ChatMemory chatMemory;
    private final ChatModel chatModel;
    private final RagTool ragTool;
    private final InMemorySessionService adkSessionService;
    private final InMemoryArtifactService adkArtifactService;
    private final DocumentCatalogService documentCatalogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_ID = "rag-studio-user";
    private static final String APP_NAME = AdkAgentConfig.APP_NAME;
    private static final String NO_RAG_SOURCES = "[{\"source\":\"(no rag)\",\"pages\":[]}]";

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

    private Runner buildRunner() {
        Map<String, String> catalog = documentCatalogService.loadCatalog();
        return AdkAgentConfig.buildRunner(chatModel, ragTool, adkSessionService, adkArtifactService, catalog);
    }

    // ADK nie tworzy sesji automatycznie — tworzymy przy pierwszym zapytaniu per conversationId.
    private void ensureSession(String conversationId) {
        try {
            var existing = adkSessionService
                    .getSession(APP_NAME, USER_ID, conversationId, Optional.empty())
                    .blockingGet();
            if (existing == null) {
                adkSessionService
                        .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), conversationId)
                        .blockingGet();
            }
        } catch (Exception e) {
            log.debug("Session check/create for {}: {}", conversationId, e.getMessage());
        }
    }

    private QueryResult adkQuery(String question, String conversationId) {
        ensureSession(conversationId);
        ragTool.clearSources(conversationId);

        Runner runner = buildRunner();
        Content userContent = Content.fromParts(Part.fromText(question));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        List<Event> events = runner
                .runAsync(USER_ID, conversationId, userContent, runConfig)
                .toList()
                .blockingGet();

        String answer = events.stream()
                .filter(Event::finalResponse)
                .findFirst()
                .map(this::extractTextToken)
                .orElse("");

        List<Map<String, Object>> sources = ragTool.pollSources(conversationId);
        return new QueryResult(answer, sources != null ? sources : List.of());
    }

    private Flux<String> adkStream(String question, String conversationId) {
        ensureSession(conversationId);
        ragTool.clearSources(conversationId);

        Runner runner = buildRunner();
        Content userContent = Content.fromParts(Part.fromText(question));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        AtomicBoolean sourcesSent = new AtomicBoolean(false);

        return Flux.from(runner.runAsync(USER_ID, conversationId, userContent, runConfig))
                .concatMap(event -> {
                    // Extract only from text parts — avoids WARN from Content::text on tool call/response events
                    String token = extractTextToken(event);
                    if (token.isEmpty()) return Flux.empty();

                    // Before the very first text token, emit __SOURCES__
                    // At this point the tool (if called) has already stored sources in RagTool
                    if (sourcesSent.compareAndSet(false, true)) {
                        String sourcesJson = buildSourcesJson(conversationId);
                        return Flux.just("__SOURCES__:" + sourcesJson, token);
                    }
                    return Flux.just(token);
                })
                .concatWith(Flux.defer(() -> {
                    // Guard: if stream produced no text at all, still emit __SOURCES__
                    if (sourcesSent.compareAndSet(false, true)) {
                        return Flux.just("__SOURCES__:" + buildSourcesJson(conversationId));
                    }
                    return Flux.empty();
                }));
    }

    // Extracts text from a stream event without triggering Content::text WARN on non-text events.
    // Content::text logs a warning when the content has functionCall or functionResponse parts.
    private String extractTextToken(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .flatMap(p -> p.text().stream())
                        .collect(Collectors.joining()))
                .orElse("");
    }

    private String buildSourcesJson(String conversationId) {
        List<Map<String, Object>> sources = ragTool.pollSources(conversationId);
        if (sources == null || sources.isEmpty()) return NO_RAG_SOURCES;
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return NO_RAG_SOURCES;
        }
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
