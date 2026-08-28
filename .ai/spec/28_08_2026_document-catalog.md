# Document Catalog — kontekst dla agenta ADK

## Status
Zaimplementowana

---

## Cel

Agent ADK nie wie co jest w bazie wiedzy — decyduje o wywołaniu `rag_search` tylko na podstawie pytania i ogólnego opisu toola. Przy pytaniach ogólnych lub gdy nazwa pliku jest nieczytelna (np. `scan_20240312.pdf`) agent pomija RAG mimo że dokumenty zawierają odpowiedź.

Rozwiązanie: przy ingeście generujemy LLM-em krótkie podsumowanie dokumentu i zapisujemy do katalogu w Redis. Przed każdym zapytaniem katalog jest wstrzykiwany do instrukcji agenta — agent wie co jest w bazie i może podjąć lepszą decyzję.

---

## Kontekst

Obecny flow:
- `PdfIngestionService.ingest()` — chunking + embedding, zero informacji o zawartości dokumentu
- `AdkAgentConfig.AGENT_INSTRUCTION` — statyczna, bez wiedzy o indeksowanych dokumentach
- `RagService.adkStream()` — buduje `userContent` z samego pytania użytkownika

Agent widzi tylko opis: `"Search the document knowledge base for relevant content."` — bez kontekstu jakie dokumenty tam są.

---

## Wymagania funkcjonalne

1. Przy ingeście dokumentu → wygeneruj 2-3 zdaniowe podsumowanie (LLM, pierwszy chunk jako kontekst)
2. Zapisz `{filename → summary}` w Redis HASH `doc:catalog`
3. Przy usunięciu dokumentu → usuń wpis z katalogu
4. Przed każdym `adkStream()` / `adkQuery()` → załaduj katalog z Redis i wstrzyknij do instrukcji agenta
5. Gdy katalog pusty → instrukcja bez sekcji dokumentów (zachowanie bez zmian)
6. Generowanie summary nie blokuje ingesztu — jeśli LLM zawiedzie, fallback do samej nazwy pliku

---

## Wymagania niefunkcjonalne

- Latency ingesztu: +1 LLM call na dokument (akceptowalne — ingest i tak jest wolny)
- Latency zapytania: +1 Redis HGETALL (mikrosekundy — pomijalnie)
- Kompatybilność: zero zmian w REST API i formacie SSE
- Brak dodatkowych zależności

---

## Proponowane rozwiązanie

### Gdzie przechowywać katalog

Redis HASH: klucz `doc:catalog`, pole = nazwa pliku, wartość = summary.

```
HSET doc:catalog "umowa-najmu.pdf" "Umowa najmu lokalu mieszkalnego przy ul. Długiej 5. Zawiera paragraf 7 o karach za opóźnienie czynszu oraz warunki zwrotu kaucji."
HSET doc:catalog "regulamin-bhp.pdf" "Przepisy BHP dla pracowników zakładu. Tematy: procedury ewakuacji, obowiązki pracodawcy, postępowanie przy wypadkach."
```

Dlaczego Redis HASH a nie vector store:
- Katalog to metadane O dokumentach, nie fragmenty do wyszukiwania
- Musi być w całości dostępny przy każdym zapytaniu (nie similarity search)
- HGETALL = jeden round-trip do Redis

### Generowanie summary

Input dla LLM: pierwsze 2-3 chunki dokumentu (po chunkowaniu, przed zapisem do vector store).

Prompt:
```
Przeczytaj poniższy fragment dokumentu i napisz 2-3 zdania opisujące:
1. O czym jest ten dokument?
2. Jakie główne tematy lub informacje zawiera?

Odpowiedź powinna pomóc zdecydować, czy ten dokument zawiera odpowiedź na pytanie użytkownika.
Pisz zwięźle, konkretnie, po polsku (lub w języku dokumentu).

Fragment dokumentu:
{first_chunks_text}
```

### Dynamiczna instrukcja agenta

`AdkAgentConfig.AGENT_INSTRUCTION` pozostaje jako szablon bazowy. `RagService` przed wywołaniem `runAsync()` buduje finalną instrukcję:

```
[stała instrukcja agenta]

Dokumenty dostępne w bazie wiedzy:
- umowa-najmu.pdf: Umowa najmu lokalu mieszkalnego przy ul. Długiej 5. Zawiera paragraf 7 o karach za opóźnienie czynszu oraz warunki zwrotu kaucji.
- regulamin-bhp.pdf: Przepisy BHP dla pracowników zakładu. Tematy: procedury ewakuacji, obowiązki pracodawcy, postępowanie przy wypadkach.

Gdy pytanie dotyczy treści tych dokumentów — wywołaj rag_search.
Gdy baza jest pusta lub pytanie nie jest związane z dokumentami — odpowiedz bezpośrednio.
```

Instrukcja jest budowana per-zapytanie (Redis HGETALL → String) — operacja poniżej 1ms.

### Problem: LlmAgent.instruction jest ustawiany przy tworzeniu beana

ADK `LlmAgent` przyjmuje instrukcję w builderze — nie można jej zmienić po stworzeniu. Rozwiązania:

**Opcja A (wybrana): Tworzenie LlmAgent per zapytanie**
`AdkAgentConfig` przestaje eksponować `LlmAgent` jako bean. `RagService` tworzy nowy `LlmAgent` przed każdym wywołaniem, z aktualnym katalogiem w instrukcji. `InMemoryRunner` też per-zapytanie.

Koszt: JVM object allocation (pomijalny). Brak IO.

**Opcja B (odrzucona): ADK session state templates**
ADK Python obsługuje `{variable}` w instrukcji (wypełniane z session state). Nieudokumentowane w Java ADK 1.8.0, ryzyko że nie działa.

**Opcja C (odrzucona): Prepend do wiadomości użytkownika**
Mniej czyste — miesza instrukcję systemową z wiadomością użytkownika. Model może traktować to jako część pytania.

---

## Architektura / Design

### Nowe klasy

```
src/main/java/com/pawer/
└── service/
    └── DocumentCatalogService.java   — Redis HSET/HGETALL/HDEL dla doc:catalog
```

### Zmiany w istniejących klasach

| Plik | Zmiana |
|------|--------|
| `PdfIngestionService.java` | Po ingeście → wywołaj `DocumentCatalogService.generateAndStore(filename, chunks)` |
| `PdfIngestionService.java` (delete) | Przed usunięciem → `DocumentCatalogService.remove(filename)` |
| `AdkAgentConfig.java` | `AGENT_INSTRUCTION` staje się `BASE_INSTRUCTION` (szablon bazowy bez sekcji dokumentów). `LlmAgent` i `InMemoryRunner` nie są już beanami — `RagService` tworzy je per-zapytanie |
| `RagService.java` | Przed `runAsync()`: pobierz katalog, zbuduj instrukcję, stwórz `LlmAgent` + `InMemoryRunner` |

### `DocumentCatalogService` — szkic

```java
@Service
public class DocumentCatalogService {
    private static final String CATALOG_KEY = "doc:catalog";

    // Generuje summary i zapisuje do Redis
    public void generateAndStore(String filename, List<Document> firstChunks, ChatClient.Builder chatClientBuilder) { ... }

    // Pobiera cały katalog: Map<filename, summary>
    public Map<String, String> loadCatalog() { ... }

    // Usuwa wpis
    public void remove(String filename) { ... }
}
```

### Dynamiczna instrukcja w `RagService`

```java
private String buildInstruction(Map<String, String> catalog) {
    if (catalog.isEmpty()) return BASE_INSTRUCTION;

    String docList = catalog.entrySet().stream()
        .map(e -> "- " + e.getKey() + ": " + e.getValue())
        .collect(Collectors.joining("\n"));

    return BASE_INSTRUCTION + "\n\nDokumenty dostępne w bazie wiedzy:\n" + docList +
           "\n\nGdy pytanie dotyczy treści tych dokumentów — wywołaj rag_search.";
}
```

---

## Zależności

### Maven
Brak nowych. Jedis (`RedisClient`) już w projekcie — dodamy dostęp do surowego klienta Redis dla operacji HASH.

### Redis
Nowy klucz `doc:catalog` (HASH). Brak nowych modułów Redis.

---

## Plan implementacji

1. Stwórz `DocumentCatalogService` z metodami `generateAndStore()`, `loadCatalog()`, `remove()`
2. Podepnij do `PdfIngestionService.ingest()` (po zapisie chunks do vector store) — `generateAndStore()`
3. Podepnij do `PdfIngestionService.delete()` — `remove()`
4. Zmień `AdkAgentConfig` — usuń beany `LlmAgent` i `InMemoryRunner`, zostaw stałą `BASE_INSTRUCTION` i `APP_NAME`
5. W `RagService` — dodaj pole `ChatModel chatModel`, dodaj metodę `buildInstruction()`, zmień `adkStream()` i `adkQuery()` żeby tworzyły agenta per-zapytanie
6. Test: ingest dokumentu → sprawdź `HGETALL doc:catalog` → zadaj pytanie → sprawdź czy agent wywołał `rag_search`

---

## Scenariusze testowe

| Scenariusz | Oczekiwane zachowanie |
|---|---|
| Ingest PDF → sprawdź Redis | `HGET doc:catalog "plik.pdf"` zwraca 2-3 zdania po polsku |
| Pytanie o dokument z katalogu | Agent wywołuje `rag_search`, sources w SSE |
| Pytanie ogólne (2+2) | Agent NIE wywołuje `rag_search`, `(no rag)` w SSE |
| Delete dokumentu | `HGET doc:catalog "plik.pdf"` → nil |
| Brak dokumentów w katalogu | Instrukcja bez sekcji dokumentów, zachowanie bez zmian |
| LLM timeout przy generowaniu summary | Fallback: zapisuje nazwę pliku jako summary, ingest nie przerywa |

---

## Pytania otwarte

1. **Ile chunków jako kontekst do summary?** Proponuję pierwsze 3 (max ~1500 tokenów) — wystarczy żeby zrozumieć dokument bez przepalania tokenów.
2. **Język summary** — powinien być wykryty automatycznie przez LLM (prompt mówi "po polsku lub w języku dokumentu"). Czy wymuszamy polski?
3. **Aktualizacja przy re-ingeście** — jeśli ten sam plik jest ingestowany ponownie, summary jest nadpisywane (HSET). OK?
