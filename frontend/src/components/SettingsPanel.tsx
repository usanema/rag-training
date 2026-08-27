import { useState, useEffect, useRef } from 'react'
import type { ChunkingStrategy, IngestResult } from '../types/chat'

interface Props {
  open: boolean
  onClose: () => void
  activeStore: string
  onStoreChange: (store: string) => void
  onIngest: () => void
}

const STORES = ['redis', 'qdrant']
const SPEED: Record<string, string> = {
  TOKEN: 'fast', PARAGRAPH: 'fast', SENTENCE: 'medium',
  HIERARCHICAL: 'medium', SEMANTIC: 'slow',
}

export default function SettingsPanel({ open, onClose, activeStore, onStoreChange, onIngest }: Props) {
  const [strategies, setStrategies] = useState<ChunkingStrategy[]>([])
  const [selectedStrategy, setSelectedStrategy] = useState('TOKEN')
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [ingestResult, setIngestResult] = useState<IngestResult | null>(null)
  const [ingestError, setIngestError] = useState('')
  const [loading, setLoading] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetch('/api/rag/strategies')
      .then(r => r.json())
      .then(setStrategies)
      .catch(() => {})
  }, [])

  // Zamknij panel klikając poza nim
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open, onClose])

  async function ingest() {
    if (!file) return
    setLoading(true)
    setIngestResult(null)
    setIngestError('')
    const form = new FormData()
    form.append('file', file)
    try {
      const res = await fetch(`/api/rag/ingest?strategy=${selectedStrategy}`, { method: 'POST', body: form })
      const data = await res.json()
      if (res.ok) { setIngestResult(data); onIngest() }
      else setIngestError(data.error ?? 'Błąd serwera')
    } catch (e: any) {
      setIngestError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function switchStore(name: string) {
    await fetch(`/api/rag/store/${name}`, { method: 'PUT' })
    onStoreChange(name)
  }

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const f = e.dataTransfer.files[0]
    if (f?.name.endsWith('.pdf')) setFile(f)
  }

  if (!open) return null

  return (
    <div className="settings-overlay">
      <div className="settings-panel" ref={panelRef}>
        <div className="settings-header">
          <span>Ustawienia</span>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>

        <div className="settings-body">
          {/* ── Vector Store ── */}
          <section>
            <div className="settings-label">Vector Store</div>
            <div className="store-row">
              {STORES.map(s => (
                <button
                  key={s}
                  className={`store-btn ${activeStore === s ? 'active' : ''}`}
                  onClick={() => switchStore(s)}
                >
                  {s}
                  {activeStore === s && <span className="store-dot" />}
                </button>
              ))}
            </div>
          </section>

          <div className="settings-divider" />

          {/* ── Strategia ── */}
          <section>
            <div className="settings-label">Strategia chunkowania</div>
            <div className="strategy-grid">
              {strategies.map(s => (
                <div
                  key={s.name}
                  className={`strategy-card ${selectedStrategy === s.name ? 'active' : ''}`}
                  onClick={() => setSelectedStrategy(s.name)}
                >
                  <div className="s-name">{s.name}</div>
                  <div className="s-desc">{s.description}</div>
                  <div className="s-tag">{SPEED[s.name] ?? '—'}</div>
                </div>
              ))}
            </div>
          </section>

          <div className="settings-divider" />

          {/* ── Upload PDF ── */}
          <section>
            <div className="settings-label">Zaindeksuj PDF</div>
            <div
              className={`drop-zone ${dragging ? 'drag-over' : ''}`}
              onClick={() => fileRef.current?.click()}
              onDragOver={e => { e.preventDefault(); setDragging(true) }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
            >
              <input ref={fileRef} type="file" accept=".pdf" style={{ display: 'none' }}
                onChange={e => e.target.files?.[0] && setFile(e.target.files[0])} />
              <div className="dz-icon">↓</div>
              <p>{file ? file.name : 'Kliknij lub przeciągnij plik PDF'}</p>
              <div className="dz-hint">Maksymalnie 50 MB</div>
            </div>

            <button className="btn-primary" disabled={!file || loading} onClick={ingest}>
              {loading ? 'Indeksuję...' : 'Zaindeksuj'}
            </button>

            {ingestResult && (
              <div className="status ok">
                ✓ {ingestResult.chunks} chunków · {ingestResult.totalMs} ms
              </div>
            )}
            {ingestError && <div className="status err">✗ {ingestError}</div>}
          </section>
        </div>
      </div>
    </div>
  )
}
