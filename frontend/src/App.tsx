import { useState, useEffect, useRef } from 'react'
import { useChat } from './hooks/useChat'
import ChatMessage from './components/ChatMessage'
import ChatInput from './components/ChatInput'
import SettingsPanel from './components/SettingsPanel'
import './App.css'

export default function App() {
  const { messages, streaming, sendMessage, clearHistory } = useChat()
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [activeStore, setActiveStore] = useState('redis')
  const bottomRef = useRef<HTMLDivElement>(null)

  // Załaduj aktywny store z backendu przy starcie
  useEffect(() => {
    fetch('/api/rag/store')
      .then(r => r.json())
      .then(d => setActiveStore(d.active))
      .catch(() => {})
  }, [])

  // Przewijaj na dół po każdej nowej wiadomości
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  return (
    <div className="layout">

      {/* ── Sidebar ─────────────────────────────────────────────────────── */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <div className="logo-icon">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M2 4h12M2 8h8M2 12h10"/>
            </svg>
          </div>
          <span>RAG Studio</span>
        </div>

        <div className="sidebar-spacer" />

        <div className="sidebar-bottom">
          <button className="nav-item" onClick={clearHistory}>
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M3 3l10 10M13 3L3 13"/>
            </svg>
            Nowy czat
          </button>

          <button className="nav-item" onClick={() => setSettingsOpen(true)}>
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="8" cy="8" r="2.5"/>
              <path d="M8 2v1.5M8 12.5V14M2 8h1.5M12.5 8H14M3.6 3.6l1.1 1.1M11.3 11.3l1.1 1.1M3.6 12.4l1.1-1.1M11.3 4.7l1.1-1.1"/>
            </svg>
            Ustawienia
            <span className="badge">{activeStore}</span>
          </button>
        </div>
      </aside>

      {/* ── Chat ────────────────────────────────────────────────────────── */}
      <div className="main">
        <div className="chat-thread">
          {messages.length === 0 && (
            <div className="empty-state">
              <div className="empty-icon">
                <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="1">
                  <path d="M4 6h24v18H18l-4 3v-3H4V6z"/>
                </svg>
              </div>
              <p>Zadaj pytanie o zaindeksowane dokumenty</p>
            </div>
          )}

          {messages.map(msg => (
            <ChatMessage key={msg.id} message={msg} />
          ))}

          <div ref={bottomRef} />
        </div>

        <div className="input-bar">
          <ChatInput onSend={sendMessage} disabled={streaming} />
        </div>
      </div>

      {/* ── Settings panel ───────────────────────────────────────────────── */}
      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        activeStore={activeStore}
        onStoreChange={setActiveStore}
      />
    </div>
  )
}
