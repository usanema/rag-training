# RAG Studio — kontekst projektu

Projekt edukacyjny do nauki RAG (Retrieval-Augmented Generation). Paweł Nowik buduje go
hands-on, ucząc się backendu Spring AI i frontendu React/TypeScript.

## Stack

| Warstwa | Technologia |
|---------|-------------|
| Backend | Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 |
| Frontend | React 19, TypeScript, Vite 8, `marked` + DOMPurify |
| Vector store | Redis Stack (domyślny) lub Qdrant — przełączalne w runtime |
| Chat model | `minimax/minimax-m2.7` przez OpenRouter (`CHAT_BASE_URL=https://openrouter.ai/api/v1`) |
| Embedding model | `nvidia/llama-nemotron-embed-vl-1b-v2:free` przez OpenRouter |
| Uruchomienie | backend: `mvn spring-boot:run`, frontend: `npm run dev` (w `frontend/`) |

## Kluczowe niuanse techniczne

### Spring AI 2.0.1 — zmiany względem 1.x
- `spring-boot-starter-parent` musi być **4.1.1** (Spring AI 2.0.1 wciąga Boot 4.x jako transitive)
- `redis.clients:jedis` pinowany na **7.4.1** w `dependencyManagement` (Boot 4.x zarządza 6.x)
- `JedisPooled` → `RedisClient.create(host, port)` w `AppConfig` (różne klasy w Jedis 7.x)
- Artifact `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`
- Dodano `spring-ai-starter-model-chat-memory` dla `MessageChatMemoryAdvisor`
- `spring.ai.openai.embedding.options.encoding-format: float` — NVIDIA embeddings nie obsługują base64
- `spring.main.allow-bean-definition-overriding: true` — Boot 4.x Jackson bean conflict
- `CHAT_BASE_URL` i `EMBEDDING_BASE_URL` muszą zawierać `/v1` (nowy SDK nie dodaje go sam)

### Spring AI 2.0.1 — streaming i advisors
- `QuestionAnswerAdvisor` **nie propaguje** `qa_retrieved_documents` przez `ChatClientResponse.context()` w streaming mode
- Źródła pobierane są bezpośrednio z `VectorStore.similaritySearch()` **przed** streamem, wysyłane jako pierwsze SSE event (`__SOURCES__:[...]`)
- `conversationId` do `MessageChatMemoryAdvisor` przekazywany przez `.advisors(a -> a.param("chat_memory_conversation_id", conversationId))`

### SSE parsing (frontend `useChat.ts`)
- Buffer dzielony po `\n\n` (granice eventów SSE)
- Każdy event może mieć wiele linii `data:`; łączone przez `join('\n')`
- `l.slice(5)` bez `.replace(/^ /, '')` — Spring wysyła `data:` + token bez dodatkowej spacji; token ze spacją na początku (granica słowa) przychodzi jako `data: word`, `slice(5)` = ` word` → spacja zachowana
- Protokół `__SOURCES__:` — prefix w danych SSE dla metadanych źródeł

### Pamięć konwersacji
- `InMemoryChatMemoryRepository` + `MessageWindowChatMemory` (max 20 wiadomości)
- Frontend generuje `conversationId` jako `useRef` (nie `useState`!) przy starcie; reset przez "Nowy czat"
- Każde pytanie wysyła `?conversationId=...` do obu endpointów

## Struktura projektu

```
src/main/java/com/pawer/
├── config/
│   ├── AppConfig.java          — beany: ChatMemory, RedisVectorStore, QdrantVectorStore
│   └── RagProperties.java      — @ConfigurationProperties(prefix="rag")
├── controller/
│   └── RagController.java      — REST API /api/rag/*
├── service/
│   ├── RagService.java         — query(), query_stream(), listDocuments()
│   ├── PdfIngestionService.java — ingest(), preview(), delete()
│   └── VectorStoreRouter.java  — przełączanie między Redis/Qdrant
├── chunking/
│   ├── ChunkingStrategy.java   — enum: TOKEN, PARAGRAPH, SENTENCE, HIERARCHICAL, SEMANTIC
│   ├── ChunkerFactory.java
│   └── strategy/               — 5 implementacji PdfChunker
└── monitoring/                 — health check przy starcie

frontend/src/
├── hooks/useChat.ts            — SSE streaming, conversationId, sendMessage, clearHistory
├── components/
│   ├── ChatMessage.tsx         — markdown render (user bubble / assistant full-width)
│   ├── ChatInput.tsx           — auto-resize textarea, Enter = wyślij
│   ├── SourceBadge.tsx         — lista źródeł z numerami stron
│   ├── DocumentList.tsx        — lista dokumentów w sidebarze (BAZA RAG)
│   └── SettingsPanel.tsx       — sliding panel: store switcher, chunking, upload PDF
└── App.tsx                     — layout: sidebar + chat
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

## Uruchamianie lokalnie

```bash
# Wymagane serwisy (Docker)
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack
docker run -d --name qdrant -p 6334:6334 qdrant/qdrant

# Backend
mvn spring-boot:run

# Frontend (osobny terminal)
cd frontend && npm run dev
# → http://localhost:3000 (Vite proxy do :8080)
```

Zmienne środowiskowe w `.env` (ładowane przez `dotenv-java`).

## Planowane funkcje (backlog)

- **Routing zapytań przez Google ADK** — przed każdym query: klasyfikacja czy pytanie dotyczy dokumentów RAG czy jest ogólne; jeśli ogólne — pomijanie `QuestionAnswerAdvisor` (tylko `ChatMemory`)
  - dependency: `com.google.adk:google-adk:1.8.0`
  - łączenie z OpenRouter przez LangChain4j `OpenAiStreamingChatModel`
