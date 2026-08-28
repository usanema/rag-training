# Query Classification Cache

## Status
Draft (odłożony — nie implementujemy teraz)

---

## Cel

Buforować wyniki klasyfikacji pytań (RAG vs GENERAL) żeby uniknąć powtórnego tool call decyzji dla identycznych pytań w ramach tej samej konwersacji.

> **Uwaga**: Ten spec powstał jako wydzielenie z `28_08_2026_query-routing-google-adk.md`. Implementujemy dopiero gdy routing ADK będzie działać i zaobserwujemy realną potrzebę optymalizacji.

---

## Kontekst

W architekturze z `28_08_2026_query-routing-google-adk.md`, każde pytanie trafia do ADK LlmAgent, który decyduje czy wywołać `RagTool`. Gdy użytkownik wysyła to samo pytanie wielokrotnie (np. odświeżenie streamu, retry), agent wykonuje tę samą decyzję od nowa.

---

## Wymagania funkcjonalne

1. Cache działa na poziomie pary `(conversationId, pytanie)` → `QueryType { RAG | GENERAL }`
2. Trafienie cache → pomiń decyzję agenta, zastosuj zapamiętany typ bezpośrednio
3. Cache jest in-memory (lokalny dla instancji aplikacji)
4. TTL lub max rozmiar do ustalenia

---

## Wymagania niefunkcjonalne

- Brak persystencji — reset przy restarcie aplikacji
- Thread-safe (reaktywne środowisko)
- Opcjonalny (flaga `rag.routing.cache.enabled`)

---

## Pytania otwarte

1. **Klucz cache**: `(conversationId + pytanie)` czy samo pytanie (shared across conversations)?
2. **TTL**: czy cache'owana decyzja powinna wygasać? Po jakim czasie?
3. **Max size**: ile wpisów trzymać? Eviction policy (LRU)?
4. **Granularność**: cache na poziomie tekstu dosłownego czy znormalizowanego (lowercase, trim)?

---

## Zależności od innych spec

- Wymaga ukończonej implementacji `28_08_2026_query-routing-google-adk.md`
