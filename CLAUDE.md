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

## Routing zapytań — Google ADK (Agentic RAG)

`RagService` ma dwa pipeline'y przełączane flagą `rag.routing.enabled`:

### ADK pipeline (domyślny)

```
User query → InMemoryRunner → LlmAgent (SpringAI → ChatModel → OpenRouter)
                                  ↓ agent decyduje (może wywołać tool wielokrotnie)
                    [wywołuje RagTool]          [nie wywołuje]
                    VectorStore.search()         odpowiedź ogólna
                    sources → sessionSources[]
                    return Map("context", ...)   
                    → SSE: __SOURCES__:[...]     → SSE: __SOURCES__:[{source:"(no rag)"}]
                    → SSE: tokeny tekstu...      → SSE: tokeny tekstu...
```

**Kluczowe klasy:**
- `RagTool` — `@Component`, instancyjna `ragSearch(query, toolContext)`. ADK wstrzykuje `ToolContext` gdy parametr ma `@Schema(name="toolContext")` — wykluczone z function schema. `sessionId = toolContext.sessionId()` — przechowuje sources per sesja w `ConcurrentHashMap`. Zwraca `Map.of("context", text)` dla LLM. `FunctionTool.create(this, "ragSearch")`.
- `AdkAgentConfig` — eksponuje `InMemorySessionService` i `InMemoryArtifactService` jako beany (współdzielone, sesje przeżywają między requestami). Dostarcza `buildRunner(chatModel, ragTool, sessionService, artifactService, catalog)` — tworzy `LlmAgent` z dynamiczną instrukcją + `Runner(agent, appName, artifactService, sessionService)`. **Brak beanów `LlmAgent` i `InMemoryRunner`** — tworzone per-query.
- `DocumentCatalogService` — Redis HASH `doc:catalog` (JedisPooled). Przy ingeście: generuje 2-3 zdaniowe summary (LLM, pierwsze 3 chunki) i zapisuje `HSET doc:catalog filename summary`. Przy delete: `HDEL`. `loadCatalog()` → HGETALL.
- `RagService.buildRunner()` — ładuje katalog z Redis, wywołuje `AdkAgentConfig.buildRunner()` z aktualnym katalogiem. Wywoływane per-query.
- `RagService.adkStream()` — po pierwszym tokenie tekstu wywołuje `ragTool.pollSources(conversationId)` → emituje `__SOURCES__:[...]`.

**Ważne niuanse ADK 1.8.0:**
- `FunctionTool.create(Object instance, String methodName)` — metoda instancyjna OK, Spring DI działa normalnie
- **`ToolContext` injection** — parametr named `"toolContext"` (przez `@Schema(name="toolContext")` lub flagę `-parameters`) jest automatycznie wstrzykiwany przez `FunctionTool.buildArguments()` i **wykluczany z function schema** (LLM go nie widzi). `ToolContext.sessionId()` → ID aktywnej sesji.
- **Multi-step retrieval** — agent może wywołać `rag_search` wielokrotnie; `ConcurrentHashMap.compute()` akumuluje sources per sesja.
- **Session management** — `InMemoryRunner` NIE tworzy sesji automatycznie. `ensureSession()` w `RagService` wywołuje `sessionService().getSession()` → jeśli null, tworzy nową przez `createSession()`.
- `Content.fromParts(Part.fromText(question))` — wiadomość wejściowa dla `runner.runAsync()`
- `RunConfig.StreamingMode.SSE` — streaming events; **partial=true tylko przy generowaniu tekstu**, NIE przy tool calls (tool calls to pełne, synchroniczne eventy)
- `SpringAI(ChatModel)` — adapter `google-adk-spring-ai` bez LangChain4j; reużywa `ChatModel` bean
- Guava `-android` nie ma `ImmutableList.toImmutableList()` — pin na `33.4.8-jre` w `dependencyManagement`
- `RedisVectorStore.MetadataField.numeric("page_number")` — Spring AI PDF reader zapisuje `page_number` jako `int`, schema musi być `NUMERIC` (nie `TEXT`)

### Legacy pipeline (fallback)

Stary pipeline Spring AI z `QuestionAnswerAdvisor` — aktywny gdy `rag.routing.enabled=false` lub gdy ADK rzuci wyjątek.
