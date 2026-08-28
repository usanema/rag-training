# RAG Studio — Frontend (React + TypeScript + Vite)

Interfejs czatu do odpytywania RAG. Kontekst backendu (Spring Boot) jest w `../CLAUDE.md`.

## Stack

| | |
|--|--|
| React | 19 |
| TypeScript | 5 |
| Vite | 6 (dev server :5173, proxy → :8080) |
| Markdown | `react-markdown` + `remark-gfm` |

## Uruchamianie

```bash
cd frontend
npm run dev   # http://localhost:5173
npm run build
npx tsc --noEmit   # type check
```

Proxy API: wszystkie `/api/*` → `http://localhost:8080` (skonfigurowane w `vite.config.ts`).

## Struktura

```
src/
├── App.tsx                  — layout: sidebar + chat + settings panel
├── App.css                  — wszystkie style (CSS variables, design tokens)
├── hooks/
│   └── useChat.ts           — stan wiadomości, SSE parsing, conversationId
├── components/
│   ├── ChatMessage.tsx      — renderowanie wiadomości (markdown, źródła)
│   ├── ChatInput.tsx        — pole input + przycisk wyślij
│   ├── DocumentList.tsx     — lista PDF-ów w bazie RAG (sidebar)
│   └── SettingsPanel.tsx    — modal: vector store, strategia, upload PDF
└── types/
    └── chat.ts              — Message, Source, ChunkingStrategy, IngestResult
```

## Kluczowe detale implementacyjne

### SSE Parsing — `useChat.ts`

Backend wysyła streamed response jako SSE (`text/event-stream`). Format eventów:

```
data:{token}\n\n
```

Ważne: token z granicą słowa (początkowa spacja) przychodzi jako `data: word` — ta spacja to **content**, nie artefakt protokołu. Dlatego parsowanie używa `l.slice(5)` bez żadnego `.replace(/^ /, '')`.

```ts
const payload = dataLines.map(l => l.slice(5)).join('\n')
```

Specjalny event do przesyłania źródeł (przed tokenami tekstu):

```ts
if (text.startsWith('__SOURCES__:')) {
  const sources = JSON.parse(text.slice(12))
  // dołącz do wiadomości asystenta
}
```

### Pamięć konwersacji

`conversationId` przechowywany w `useRef(generateId())` — nie powoduje rerenderów, nie resetuje się między pytaniami. Przekazywany do każdego requesta:

```ts
/api/rag/query/stream?q=...&conversationId=${conversationId.current}
```

`clearHistory()` generuje nowe `conversationId` + resetuje `messages`.

### Obsługa źródeł

Każda wiadomość asystenta może zawierać `sources: Source[]`. `ChatMessage` renderuje je poniżej tekstu odpowiedzi jako clickable karty (nazwa pliku + score):

```ts
interface Source {
  fileName: string
  score: number
  excerpt?: string
}
```

### Lista dokumentów

`DocumentList` wyświetla unikalne nazwy PDF-ów zaindeksowanych w aktywnym vector store. Dane z `/api/rag/documents` (pobierane przy starcie aplikacji i po każdej ingestion). Przycisk odświeżania z animacją spin podczas ładowania.

### SettingsPanel

Modal zamykany kliknięciem poza nim (event listener na `document`). Po udanej ingestion wywołuje `onIngest()` → `refreshDocuments()` w `App.tsx`.

## Design tokens (App.css)

```css
--bg: #0f1117;
--surface: #1a1d27;
--border: #2a2d3a;
--accent: #6c63ff;
--text: #e8eaf0;
--text-muted: #8b90a0;
```

Dark theme, monochromatyczny z fioletowym akcentem.

## API calls (wszystkie przez proxy /api)

| Endpoint | Skąd wołany |
|----------|-------------|
| `GET /api/rag/store` | App.tsx (mount) |
| `PUT /api/rag/store/:name` | SettingsPanel |
| `GET /api/rag/documents` | App.tsx (mount + po ingestion) |
| `GET /api/rag/strategies` | SettingsPanel (mount) |
| `POST /api/rag/ingest` | SettingsPanel |
| `GET /api/rag/query/stream` | useChat.ts (SSE) |
