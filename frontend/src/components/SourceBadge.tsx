import type { Source } from '../types/chat'

interface Props {
  sources: Source[]
}

export default function SourceBadge({ sources }: Props) {
  if (!sources.length) return null

  return (
    <div className="sources">
      <div className="sources-label">Źródła</div>
      {sources.map(s => (
        <div key={s.source} className="source-row">
          <span className="source-file">{s.source}</span>
          <div className="source-pages">
            {s.pages.map(p => (
              <span key={p} className="pill">s. {p}</span>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
