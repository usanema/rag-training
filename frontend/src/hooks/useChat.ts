import { useState, useCallback, useRef } from 'react'
import type { Message, Source } from '../types/chat'

function generateId(): string {
  return Math.random().toString(36).slice(2)
}

function tryParseSources(data: string): Source[] | null {
  if (!data.startsWith('__SOURCES__:')) return null
  try {
    return JSON.parse(data.slice('__SOURCES__:'.length)) as Source[]
  } catch {
    return null
  }
}

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState(false)

  // conversationId identyfikuje wątek rozmowy — backend przechowuje historię per ID.
  // useRef zamiast useState bo zmiana ID nie powinna przerenderowywać komponentu.
  const conversationId = useRef(generateId())

  const updateMessage = useCallback((id: string, patch: Partial<Message>) => {
    setMessages(prev =>
      prev.map(m => (m.id === id ? { ...m, ...patch } : m))
    )
  }, [])

  const sendMessage = useCallback(async (question: string) => {
    if (!question.trim() || streaming) return

    const userMsg: Message = { id: generateId(), role: 'user', text: question }
    setMessages(prev => [...prev, userMsg])

    const assistantId = generateId()
    setMessages(prev => [...prev, { id: assistantId, role: 'assistant', text: '', loading: true }])
    setStreaming(true)

    const url = `/api/rag/query/stream?q=${encodeURIComponent(question)}&conversationId=${conversationId.current}`

    try {
      const res = await fetch(url)

      if (!res.ok || !res.body) {
        updateMessage(assistantId, { text: 'Błąd serwera.', loading: false })
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let text = ''
      let sources: Source[] | undefined

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE events są oddzielone podwójnym newline — każdy event może mieć
        // wiele linii "data:" które razem składają się na jeden chunk tekstu.
        const events = buffer.split('\n\n')
        buffer = events.pop() ?? ''

        for (const event of events) {
          const dataLines = event.split('\n').filter(l => l.startsWith('data:'))
          if (!dataLines.length) continue

          const payload = dataLines.map(l => l.slice(5)).join('\n')

          const parsed = tryParseSources(payload)
          if (parsed) {
            sources = parsed
            continue
          }

          text += payload
          updateMessage(assistantId, { text, loading: true })
        }
      }

      updateMessage(assistantId, {
        text: text || 'Brak odpowiedzi.',
        sources: sources ?? [],
        loading: false,
      })
    } catch {
      updateMessage(assistantId, { text: 'Błąd połączenia z serwerem.', loading: false })
    } finally {
      setStreaming(false)
    }
  }, [streaming, updateMessage])

  // Nowy czat = nowy conversationId — backend zaczyna świeżą historię
  const clearHistory = useCallback(() => {
    setMessages([])
    conversationId.current = generateId()
  }, [])

  return { messages, streaming, sendMessage, clearHistory }
}
