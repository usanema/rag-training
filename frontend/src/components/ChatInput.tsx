import { useState, useRef, useEffect } from 'react'

interface Props {
  onSend: (text: string) => void
  disabled: boolean
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [value, setValue] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (ref.current) {
      ref.current.style.height = 'auto'
      ref.current.style.height = ref.current.scrollHeight + 'px'
    }
  }, [value])

  function handleSend() {
    const text = value.trim()
    if (!text || disabled) return
    onSend(text)
    setValue('')
  }

  return (
    <div className="chat-input-wrap">
      <textarea
        ref={ref}
        className="chat-input"
        value={value}
        rows={1}
        placeholder="Zadaj pytanie o zaindeksowane dokumenty..."
        disabled={disabled}
        onChange={e => setValue(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            handleSend()
          }
        }}
      />
      <button
        className="send-btn"
        disabled={disabled || !value.trim()}
        onClick={handleSend}
      >
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" width="13" height="13">
          <path d="M13 8H3M9 4l4 4-4 4"/>
        </svg>
      </button>
    </div>
  )
}
