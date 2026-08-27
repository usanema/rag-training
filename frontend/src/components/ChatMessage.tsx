import DOMPurify from 'dompurify'
import { marked } from 'marked'
import type { Message } from '../types/chat'
import SourceBadge from './SourceBadge'

interface Props {
  message: Message
}

export default function ChatMessage({ message }: Props) {
  if (message.role === 'user') {
    return (
      <div className="message message-user">
        <div className="bubble">{message.text}</div>
      </div>
    )
  }

  const html = message.text
    ? DOMPurify.sanitize(marked.parse(message.text) as string)
    : ''

  return (
    <div className="message message-assistant">
      <div className={`answer-box${message.loading ? ' loading' : ''}`}>
        {message.loading && !message.text
          ? <span className="cursor" />
          : <div dangerouslySetInnerHTML={{ __html: html }} />
        }
        {message.loading && message.text && <span className="cursor" />}
      </div>
      {!message.loading && message.sources && message.sources.length > 0 && (
        <SourceBadge sources={message.sources} />
      )}
    </div>
  )
}
