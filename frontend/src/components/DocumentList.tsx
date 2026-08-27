interface Props {
  documents: string[]
  loading: boolean
  onRefresh: () => void
}

export default function DocumentList({ documents, loading, onRefresh }: Props) {
  return (
    <div className="doc-list">
      <div className="doc-list-header">
        <span className="doc-list-title">Baza RAG</span>
        <button
          className="doc-refresh-btn"
          onClick={onRefresh}
          disabled={loading}
          title="Odśwież listę"
        >
          <svg
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            className={loading ? 'spin' : ''}
          >
            <path d="M13.5 8A5.5 5.5 0 1 1 8 2.5c1.8 0 3.4.87 4.4 2.2" />
            <path d="M12 2v3h-3" />
          </svg>
        </button>
      </div>

      {documents.length === 0 ? (
        <div className="doc-empty">
          {loading ? 'Ładowanie…' : 'Brak dokumentów'}
        </div>
      ) : (
        <ul className="doc-items">
          {documents.map(name => (
            <li key={name} className="doc-item" title={name}>
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M4 2h6l3 3v9H4V2z" />
                <path d="M10 2v3h3" />
              </svg>
              <span>{name}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
