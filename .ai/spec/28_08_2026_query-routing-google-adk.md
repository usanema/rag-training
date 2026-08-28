# Query Routing via Google ADK + RagTool

## Status
Zaimplementowana

---

## Cel

Zastąpić istniejący pipeline `ChatClient + QuestionAnswerAdvisor` przez ADK `LlmAgent` wyposażony w `RagTool`. Agent sam decyduje, czy wywołać narzędzie (pytanie dotyczy dokumentów) czy odpowiedzieć z wiedzy ogólnej. Eliminuje to potrzebę osobnego klasyfikatora — routing wynika z autonomicznego użycia narzędzia przez model.

---

## Kontekst

Obecny flow:

```
User → RagService → ChatClient
                      ├── MessageChatMemoryAdvisor
                      └── QuestionAnswerAdvisor  ← zawsze wyszukuje w vector store
                      → LLM → SSE tokens
```

`QuestionAnswerAdvisor` zawsze odpytuje vector store, nawet dla pytań ogólnych. Brak możliwości pominięcia bez warunkowej logiki po stronie aplikacji.

---

## Wymagania funkcjonalne

1. Agent musi mieć zarejestrowany `RagTool` opisujący kiedy i jak przeszukiwać bazę wiedzy
2. Gdy agent wywoła `RagTool` → znalezione dokumenty trafiają jako `__SOURCES__:[...]` w SSE
3. Gdy agent **nie** wywoła `RagTool` → SSE wysyła `__SOURCES__:["(no rag)"]`
4. Pamięć konwersacji (`conversationId`) musi działać — ADK zarządza sesją przez `InMemorySessionService`
5. REST API i format SSE pozostają bez zmian (frontend bez modyfikacji)
6. Flaga `rag.routing.enabled: false` → fallback do starego pipeline (bez ADK)

---

## Wymagania niefunkcjonalne

- Brak dodatkowego LLM call przed streamem (routing = decyzja agenta podczas generowania, nie osobny request)
- Błąd ADK → fallback do starego pipeline, log WARN
- Kompatybilność wsteczna: zero zmian w REST API i formacie SSE

---

## Proponowane rozwiązanie

ADK `LlmAgent` z `RagTool` jako jedynym narzędziem. Agent:
1. Analizuje pytanie
2. Jeśli potrzebuje wiedzy z dokumentów → wywołuje `RagTool(query)` → dostaje listę fragmentów
3. Generuje odpowiedź (z lub bez kontekstu z narzędzia)
4. Strumieniuje tokeny przez SSE

`ChatModel` bean (Spring AI, już skonfigurowany pod OpenRouter) jest reużywany przez ADK przez `google-adk-spring-ai` adapter. Brak LangChain4j.

### Alternatywy odrzucone

| Podejście | Dlaczego odrzucone |
|---|---|
| Osobny klasyfikator + warunkowe advisory | Dwa LLM calle na pytanie; klasyfikator to dodatkowa złożoność |
| Zostawić QuestionAnswerAdvisor zawsze | Nie rozwiązuje problemu pytań ogólnych |
| ADK + LangChain4j | Niepotrzebna zależność — Spring AI już w projekcie |

---

## Architektura / Design

### Nowy flow

```
User
 │
 ▼
RagService.query_stream(q, conversationId)
 │
 ├── rag.routing.enabled = false → [stary pipeline, fallback]
 │
 └── rag.routing.enabled = true →
      │
      ▼
   ADK InMemoryRunner.runAsync(session, q)
      │
      ▼
   LlmAgent (SpringAI model = ChatModel bean)
      │
      ├─ [agent decyduje: wywołać RagTool?]
      │
      ├── TAK → RagTool.execute(q)
      │           └── VectorStore.similaritySearch(q)
      │               └── return sformatowane fragmenty
      │
      └── NIE → (bez tool call)
      │
      ▼
   Agent generuje odpowiedź + stream tokenów
      │
      ▼
   Konwersja event stream ADK → Flux<String>
      ├── __SOURCES__:[docs]  lub  __SOURCES__:["(no rag)"]
      └── tokeny tekstu...
```

### Nowe klasy

```
src/main/java/com/pawer/
└── routing/
    ├── RagTool.java          — ADK FunctionTool: VectorStore.similaritySearch()
    └── AdkAgentConfig.java   — @Configuration: LlmAgent bean, InMemorySessionService
```

### Zmiany w istniejących klasach

| Plik | Zmiana |
|------|--------|
| `RagService.java` | `query()` i `query_stream()` — warunkowe wywołanie ADK Runner lub stary pipeline |
| `AppConfig.java` | Brak zmian |
| `pom.xml` | Nowe zależności ADK |
| `application.yml` | Nowy klucz `rag.routing.enabled` |
| `RagProperties.java` | Nowy record `Routing(boolean enabled)` |

### `RagTool` — szkic

```java
// ADK Tool deklaracja (dokładne API zweryfikowane podczas implementacji)
@Component
public class RagTool {
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    // Metoda opisana jako FunctionTool dla ADK
    // name: "rag_search"
    // description: "Search the document knowledge base for relevant content.
    //               Call this when the question is about specific documents,
    //               contracts, reports, or any content that might be in the knowledge base."
    // param query: String — search query derived from user question

    public String execute(String query) {
        var search = ragProperties.search();
        List<Document> docs = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(search.topK())
                .similarityThreshold(search.similarityThreshold())
                .build());
        return formatAsContext(docs);   // zwraca tekst dla agenta + zachowuje docs dla __SOURCES__
    }
}
```

### `AdkAgentConfig` — szkic

```java
@Configuration
public class AdkAgentConfig {

    @Bean
    public InMemorySessionService sessionService() {
        return new InMemorySessionService();
    }

    @Bean
    public LlmAgent ragAgent(ChatModel chatModel, RagTool ragTool) {
        SpringAI model = new SpringAI(chatModel, chatModelName);
        return LlmAgent.builder()
            .name("rag-agent")
            .model(model)
            .instruction(AGENT_INSTRUCTION)
            .tools(ragTool)   // dokładne API: zweryfikować w implementacji
            .build();
    }

    @Bean
    public InMemoryRunner adkRunner(LlmAgent ragAgent, InMemorySessionService sessionService) {
        return new InMemoryRunner(ragAgent, sessionService);
    }
}
```

### `RagService` — warunkowa logika

```java
// Uproszczony pseudokod
public Flux<String> query_stream(String question, String conversationId) {
    if (!ragProperties.routing().enabled()) {
        return legacyStream(question, conversationId);   // stary pipeline
    }
    return adkStream(question, conversationId);
}

private Flux<String> adkStream(String question, String conversationId) {
    // 1. Utwórz/pobierz sesję ADK dla conversationId
    // 2. Uruchom runner.runAsync(session, question)
    // 3. Iteruj po eventach ADK:
    //    - ToolCallEvent z "rag_search" → zbierz docs → emituj __SOURCES__:[...]
    //    - TextEvent → emituj token
    //    - Brak ToolCallEvent do końca → emituj __SOURCES__:["(no rag)"]
    // 4. Zwróć jako Flux<String>
}
```

### Instrukcja agenta

```
You are a helpful assistant with access to a document knowledge base.

When the user's question is about specific documents, reports, contracts, data,
or any content that might exist in indexed files — call the rag_search tool.

When the question is about general knowledge, math, programming concepts,
or anything not tied to specific documents — answer directly without the tool.

Always answer in the same language as the user's question.
```

### Pamięć konwersacji

ADK `InMemorySessionService` przechowuje historię per `sessionId`. Mapujemy `conversationId` → `sessionId` ADK (mogą być tą samą wartością).

---

## Zależności

### Maven (pom.xml)

```xml
<!-- Google ADK core -->
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk</artifactId>
    <version>1.8.0</version>
</dependency>

<!-- ADK Spring AI adapter — opakowuje ChatModel dla ADK, bez LangChain4j -->
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-spring-ai</artifactId>
    <version>1.8.0</version>
</dependency>
```

> **Ryzyko**: `google-adk-spring-ai:1.8.0` musi być na Maven Central. Weryfikujemy w kroku 1. Fallback: własny `BaseLlm` adapter oparty na `RestClient`.

### `application.yml`

```yaml
rag:
  routing:
    enabled: true
```

---

## Plan implementacji

1. Dodaj zależności, sprawdź że projekt się buduje. Zweryfikuj dostępność `google-adk-spring-ai:1.8.0`
2. Przeczytaj dokładne API: jak ADK Java definiuje FunctionTool (annotacje? builder? interfejs?)
3. Stwórz `RagTool.java` — opakuj `VectorStore.similaritySearch()`, zwróć sformatowany String
4. Stwórz `AdkAgentConfig.java` — `InMemorySessionService`, `LlmAgent`, `InMemoryRunner` beany
5. Dodaj `Routing(boolean enabled)` do `RagProperties.java`
6. W `RagService`: dodaj `adkStream()` konwertujący ADK event stream → `Flux<String>` z `__SOURCES__` i tokenami
7. Podepnij `adkStream()` warunkowo w `query_stream()` i analogicznie w `query()`
8. Test manualny: pytanie o dokument → `__SOURCES__:[{...}]`, pytanie ogólne → `__SOURCES__:["(no rag)"]`

---

## Scenariusze testowe

| Pytanie | Oczekiwane zachowanie | Weryfikacja |
|---------|----------------------|-------------|
| "Co mówi paragraf 3 umowy?" | RagTool wywołany, źródła w SSE | Logi ADK + UI |
| "Ile to 2 + 2?" | Brak tool call, `__SOURCES__:["(no rag)"]` | SSE log |
| "Wyjaśnij mi REST API" | Brak tool call, brak vector search | Logi VectorStore |
| "Znajdź wzmiankę o karach umownych" | RagTool wywołany | UI źródła |
| Błąd ADK (symulacja) | Fallback do legacy pipeline, log WARN | Odpowiedź mimo błędu |
| `rag.routing.enabled: false` | Stary pipeline, QA Advisor zawsze | Brak wywołania ADK |
| Pytanie wieloturowe "a co z tym paragrafem?" | Pamięć konwersacji działa w ADK sesji | Poprawna odpowiedź |

---

## Pytania otwarte

1. ~~LangChain4j~~ — **Rozwiązane**: `google-adk-spring-ai`, brak LangChain4j
2. ~~Model klasyfikatora~~ — **Rozwiązane**: ten sam `ChatModel` bean (OpenRouter)
3. ~~Caching~~ — **Odłożone**: osobny spec `28_08_2026_query-classification-cache.md`
4. ~~Info w UI~~ — **Rozwiązane**: `__SOURCES__:["(no rag)"]` gdy brak tool call
5. ~~ADK Runner lifecycle~~ — **Rozwiązane**: `InMemoryRunner` per-request (nowy runner na każde query)
6. **FunctionTool API** — dokładny sposób rejestracji narzędzia w ADK Java (annotacje vs interfejs) — weryfikacja w kroku 2 implementacji
