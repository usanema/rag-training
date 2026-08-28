# RAG Studio — Backend (Spring Boot)

Projekt edukacyjny do nauki RAG. Paweł Nowik — backend developer, uczy się Spring AI hands-on.
Frontend znajduje się w `frontend/` — jego kontekst jest w `frontend/CLAUDE.md`.

## Stack

| | |
|--|--|
| Java | 21 |
| Spring Boot | 4.1.1 (wymagane przez Spring AI 2.0.1 — wciąga Boot 4.x jako transitive) |
| Spring AI | 2.0.1 |
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
│   ├── RagService.java         — query(), query_stream(), listDocuments()
│   ├── PdfIngestionService.java — ingest(), preview(), delete()
│   └── VectorStoreRouter.java  — przełączanie Redis/Qdrant
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

### Konfiguracja `application.yml`
- `spring.main.allow-bean-definition-overriding: true` — Boot 4.x Jackson bean conflict
- `spring.ai.openai.embedding.options.encoding-format: float` — NVIDIA embeddings nie obsługują base64
- `CHAT_BASE_URL` i `EMBEDDING_BASE_URL` muszą zawierać `/v1` — nowy `openai-java` SDK nie dodaje go automatycznie

### Streaming i advisors — pułapka
`QuestionAnswerAdvisor` **nie propaguje** `qa_retrieved_documents` do `ChatClientResponse.context()` w trybie streaming (Spring AI 2.0.1). Efekt: `response.getMetadata().get(RETRIEVED_DOCUMENTS)` zawsze null.

**Rozwiązanie w `RagService.query_stream()`**: wyszukujemy dokumenty bezpośrednio z `VectorStore` przed startem streamu i wysyłamy je jako pierwsze SSE event (`__SOURCES__:[...]`). RAG advisor nadal augmentuje prompt własnym wyszukiwaniem.

### Pamięć konwersacji
- Bean: `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` (max 20 wiadomości)
- `conversationId` przekazywany przez `.advisors(a -> a.param("chat_memory_conversation_id", id))`
- Konstruktor `MessageChatMemoryAdvisor.Builder` **nie ma** metody `.conversationId()` w 2.0.1

### SSE — format eventów
Spring wysyła `data:{token}\n\n` bez dodatkowej spacji. Token z początkową spacją (granica słowa) przychodzi jako `data: word`. Parsowanie: `l.slice(5)` — **bez** `.replace(/^ /, '')`, bo ta spacja to content, nie artefakt protokołu SSE.

## Planowane funkcje

- **Routing zapytań przez Google ADK** (`com.google.adk:google-adk:1.8.0`) — klasyfikacja pytania przed każdym query: RAG vs. ogólne. Jeśli ogólne → pomijamy `QuestionAnswerAdvisor`. Połączenie z OpenRouter przez LangChain4j `OpenAiStreamingChatModel`.
