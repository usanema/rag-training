export type Role = 'user' | 'assistant'

export interface Source {
  source: string   // nazwa pliku
  pages: string[]  // numery stron
}

export interface Message {
  id: string
  role: Role
  text: string
  sources?: Source[]
  loading?: boolean  // true gdy stream jeszcze trwa
}

export interface ChunkingStrategy {
  name: string
  description: string
}

export interface IngestResult {
  file: string
  strategy: string
  chunks: number
  chunkingMs: number
  embeddingMs: number
  totalMs: number
}
