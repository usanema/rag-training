# RAG Studio — Backend (Spring Boot)

Projekt edukacyjny do nauki RAG. Paweł Nowik — backend developer, uczy się Spring AI hands-on.
Frontend znajduje się w `frontend/` — jego kontekst jest w `frontend/CLAUDE.md`.

## Stack

| | |
|--|--|
| Java | 21 |
| Spring Boot | 4.1.1 (wymagane przez Spring AI 2.0.1 — wciąga Boot 4.x jako transitive) |
| Spring AI | 2.0.1 |
| Google ADK | 1.8.0 (`google-adk` + `google-adk-spring-ai`) |
| Vector stores | Redis Stack (domyślny) + Qdrant — przełączalne w runtime |
| Chat model | `minimax/minimax-m2.7` przez OpenRouter |
| Embedding | `nvidia/llama-nemotron-embed-vl-1b-v2:free` przez OpenRouter |

## Uruchamianie

```bash
# Wymagane serwisy (Docker)
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack
docker run -d --name qdrant -p 6334:6334 qdrant/qdrant

mvn spring-boot:run
# → http://localhost:8080
```

Zmienne środowiskowe w `.env` (ładowane przez `dotenv-java` przy starcie).

## Struktura kodu

```
src/main/java/com/pawer/
├── config/
│   ├── AppConfig.java          — ChatMemory bean, RedisVectorStore, QdrantVectorStore
│   └── RagProperties.java      — @ConfigurationProperties(prefix="rag")
├── controller/
│   ├── RagController.java      — REST API /api/rag/*
│   └── GlobalExceptionHandler.java
├── service/
│   ├── RagService.java         — query(), query_stream(), listDocuments(); routing ADK/legacy
│   ├── PdfIngestionService.java — ingest(), preview(), delete()
│   └── VectorStoreRouter.java  — przełączanie Redis/Qdrant
├── routing/
│   ├── RagTool.java            — ADK FunctionTool: VectorStore.similaritySearch()
│   └── AdkAgentConfig.java     — LlmAgent + InMemoryRunner beany
├── chunking/
│   ├── ChunkingStrategy.java   — enum: TOKEN, PARAGRAPH, SENTENCE, HIERARCHICAL, SEMANTIC
│   ├── ChunkerFactory.java
│   └── strategy/               — 5 implementacji PdfChunker
└── monitoring/                 — health check modelu i embeddingu przy starcie
```

## REST API

| Method | Path | Opis |
|--------|------|------|
| GET | `/api/rag/info` | aktywny store |
| GET | `/api/rag/store` | aktywny store + dostępne |
| PUT | `/api/rag/store/{name}` | przełącz store |
| GET | `/api/rag/documents` | lista zaindeksowanych PDF (unikalne `source`) |
| GET | `/api/rag/strategies` | dostępne strategie chunkowania |
| POST | `/api/rag/ingest` | zaindeksuj PDF (multipart) |
| POST | `/api/rag/preview` | podgląd chunków bez zapisu |
| DELETE | `/api/rag/delete?fileName=` | usuń dokumenty |
| GET | `/api/rag/query?q=&conversationId=` | odpowiedź bez streamingu |
| GET | `/api/rag/query/stream?q=&conversationId=` | SSE streaming |
| GET | `/api/rag/similarity?a=&b=` | cosine similarity dwóch tekstów |

## Krytyczne niuanse Spring AI 2.0.1

### Zależności i wersje
- **Jedis pinowany na 7.4.1** w `dependencyManagement` — Boot 4.x zarządza 6.x, a Redis Store wymaga 7.x (`RedisClient` interface)
- `JedisPooled` → `RedisClient.create(host, port)` w `AppConfig` — różne klasy w Jedis 7.x (rodzeństwo pod `UnifiedJedis`, nie można rzutować)
- Artifact zmieniony: `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`
- Dodano: `spring-ai-starter-model-chat-memory`
- **Guava pinowana na 33.4.8-jre** w `dependencyManagement` — Qdrant wciąga wariant `-android`, który nie ma `ImmutableList.toImmutableList()` (wymagane przez ADK 1.8.0)

### Konfiguracja `application.yml`
- `spring.main.allow-bean-definition-overriding: true` — Boot 4.x Jackson bean conflict
- `spring.ai.openai.embedding.options.encoding-format: float` — NVIDIA embeddings nie obsługują base64
- `CHAT_BASE_URL` i `EMBEDDING_BASE_URL` muszą zawierać `/v1` — nowy `openai-java` SDK nie dodaje go automatycznie

### Streaming i advisors — pułapka (legacy pipeline)
`QuestionAnswerAdvisor` **nie propaguje** `qa_retrieved_documents` do `ChatClientResponse.context()` w trybie streaming (Spring AI 2.0.1). Efekt: `response.getMetadata().get(RETRIEVED_DOCUMENTS)` zawsze null.

**Rozwiązanie w `RagService.legacyStream()`**: wyszukujemy dokumenty bezpośrednio z `VectorStore` przed startem streamu i wysyłamy je jako pierwsze SSE event (`__SOURCES__:[...]`). RAG advisor nadal augmentuje prompt własnym wyszukiwaniem.

### Pamięć konwersacji
- Bean: `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` (max 20 wiadomości) — używany w legacy pipeline
- `conversationId` przekazywany przez `.advisors(a -> a.param("chat_memory_conversation_id", id))`
- Konstruktor `MessageChatMemoryAdvisor.Builder` **nie ma** metody `.conversationId()` w 2.0.1
- W ADK pipeline pamięć zarządzana przez `InMemoryRunner` (ADK session per `conversationId`)

### SSE — format eventów
Spring wysyła `data:{token}\n\n` bez dodatkowej spacji. Token z początkową spacją (granica słowa) przychodzi jako `data: word`. Parsowanie: `l.slice(5)` — **bez** `.replace(/^ /, '')`, bo ta spacja to content, nie artefakt protokołu SSE.

## Routing zapytań — Google ADK

`RagService` ma dwa pipeline'y przełączane flagą `rag.routing.enabled`:

### ADK pipeline (domyślny)

```
User query → InMemoryRunner → LlmAgent (SpringAI adapter → ChatModel → OpenRouter)
                                  ↓ agent decyduje
                    [wywołuje RagTool]          [nie wywołuje]
                    VectorStore.search()         odpowiedź ogólna
                    → SSE: __SOURCES__:[...]     → SSE: __SOURCES__:[{source:"(no rag)"}]
                    → SSE: tokeny tekstu...      → SSE: tokeny tekstu...
```

**Kluczowe klasy:**
- `RagTool` — `@Component`, metoda instancyjna `ragSearch(String query)` z adnotacją `@Annotations.Schema`. Zwraca `Map<String,Object>` z `context` (tekst dla agenta) i `sources_json` (JSON dla SSE). `FunctionTool.create(this, "ragSearch")` rejestruje ją jako ADK tool.
- `AdkAgentConfig` — `LlmAgent.builder().model(new SpringAI(chatModel)).tools(ragTool.toFunctionTool()).build()` + `InMemoryRunner(agent, "rag-studio")`

**Ważne niuanse ADK 1.8.0:**
- `FunctionTool.create(Object instance, String methodName)` — metoda może być instancyjna (nie musi być `static`), więc Spring DI działa normalnie
- `@com.google.adk.tools.Annotations.Schema(name, description)` — na metodzie i parametrach
- `SpringAI(ChatModel)` z `google-adk-spring-ai` — adapter bez LangChain4j; reużywa istniejący `ChatModel` bean skonfigurowany pod OpenRouter
- `Content.fromParts(Part.fromText(question))` — tak tworzy się wiadomość wejściową dla `runner.runAsync()`
- `RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build()` — streaming mode
- `Event.content().map(Content::text)` — ekstrakcja tokenu tekstu z eventu
- `Part.functionResponse()` → `Optional<FunctionResponse>` → `.response()` → `Optional<Map<String,Object>>` — tak odczytujemy wynik tool call z event stream
- `InMemoryRunner` zarządza sesjami wewnętrznie — brak potrzeby ręcznego `InMemorySessionService`
- Guava `-android` nie ma `ImmutableList.toImmutableList()` — pin na `-jre` w `dependencyManagement`

### Legacy pipeline (fallback)

Stary pipeline Spring AI z `QuestionAnswerAdvisor` — aktywny gdy `rag.routing.enabled=false` lub gdy ADK rzuci wyjątek.
