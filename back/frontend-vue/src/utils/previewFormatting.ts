import { marked, Renderer } from 'marked'

marked.setOptions({
  breaks: true,
  gfm: true,
})

/**
 * 将本地文件系统路径转换为后端 API 可访问的图片 URL。
 * 支持格式：
 *   - 绝对路径: /Users/.../uploads/media/{docId}/{filename}
 *   - 相对路径: ../uploads/media/{docId}/{filename}
 *   - 已经是 API URL 的路径直接返回
 */
function resolveMediaPath(src: string): string {
  if (!src) return src
  // 已经是 API URL 或 HTTP URL，不做处理
  if (src.startsWith('/api/') || src.startsWith('http://') || src.startsWith('https://') || src.startsWith('data:')) {
    return src
  }
  // 本地路径：提取最后两段 {docId}/{filename}
  const normalized = src.replace(/\\/g, '/')
  const parts = normalized.split('/').filter(Boolean)
  if (parts.length >= 2) {
    const filename = parts[parts.length - 1]
    const docId = parts[parts.length - 2]
    // docId 应为纯数字（文档ID目录）
    if (/^\d+$/.test(docId) && filename) {
      return `/api/media/${docId}/${filename}`
    }
  }
  // 无法解析，原样返回（会触发浏览器 404，但不会静默失败）
  return src
}

/**
 * 自定义 marked Renderer：在渲染 Markdown 图片时将本地路径重写为 API URL，
 * 避免浏览器因为访问服务器本地路径而显示破碎图片符号。
 */
const customRenderer = new Renderer()
customRenderer.image = function ({ href, title, text }: { href: string; title: string | null; text: string }) {
  const apiSrc = resolveMediaPath(href || '')
  const titleAttr = title ? ` title="${title}"` : ''
  return `<img src="${apiSrc}" alt="${text}"${titleAttr} style="max-width:100%;border-radius:8px;display:block;margin:8px 0;" />`
}

marked.use({ renderer: customRenderer })

const MENTION_PATTERN = /(^|[\s(])(@[^\s`<>()\[\]{}]+)/g

export function extractMentionTokens(text: string): string[] {
  if (!text) return []

  const seen = new Set<string>()
  const tokens: string[] = []

  for (const match of text.matchAll(MENTION_PATTERN)) {
    const token = (match[2] || '').trim().replace(/[.,;:!?。！？、，；：]+$/, '')
    if (!token || seen.has(token)) continue
    seen.add(token)
    tokens.push(token)
  }

  return tokens
}

export function preprocessMarkdown(text: string): string {
  if (!text) return ''

  const lines = text.split('\n')
  const newLines: string[] = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()

    // Check if the current line looks like a table separator line
    const isSeparator = 
      trimmed.startsWith('|') && 
      /^[|:\-\s]+$/.test(trimmed) && 
      trimmed.includes('-')

    if (isSeparator) {
      // 1. Normalize the separator line
      const parts = line.split('|')
      const normalizedParts = parts.map((p, idx) => {
        if (idx === 0 && p.trim() === '') return ''
        if (idx === parts.length - 1 && p.trim() === '') return ''
        const cell = p.trim()
        if (!cell) return ' '
        const left = cell.startsWith(':') ? ':' : ''
        const right = cell.endsWith(':') ? ':' : ''
        const dashes = cell.replace(/:/g, '').replace(/\s+/g, '-').replace(/-+/g, '---')
        return ' ' + left + dashes + right + ' '
      })
      const normalizedSeparator = normalizedParts.join('|')

      // 2. Ensure there is a header line before it, and that header line is preceded by an empty line
      if (newLines.length > 0) {
        const prevLine = newLines[newLines.length - 1]
        if (newLines.length > 1) {
          const prePrevLine = newLines[newLines.length - 2]
          if (prePrevLine.trim() !== '' && !prePrevLine.trim().startsWith('|') && prevLine.trim().startsWith('|')) {
            newLines.splice(newLines.length - 1, 0, '')
          }
        } else if (newLines.length === 1 && prevLine.trim().startsWith('|')) {
          newLines.unshift('')
        }
      }

      newLines.push(normalizedSeparator)
    } else {
      const isHeaderCandidate = trimmed.startsWith('|')
      if (isHeaderCandidate && newLines.length > 0) {
        const prevLine = newLines[newLines.length - 1]
        if (prevLine.trim() !== '' && !prevLine.trim().startsWith('|')) {
          const nextLine = lines[i + 1]
          if (nextLine) {
            const nextTrimmed = nextLine.trim()
            const nextIsSeparator = 
              nextTrimmed.startsWith('|') && 
              /^[|:\-\s]+$/.test(nextTrimmed) && 
              nextTrimmed.includes('-')
            if (nextIsSeparator) {
              newLines.push('')
            }
          }
        }
      }
      newLines.push(line)
    }
  }

  return newLines.join('\n')
}

export function renderPreviewMarkdown(text: string): string {
  if (!text) return ''

  const preprocessed = preprocessMarkdown(text)

  const highlighted = preprocessed.replace(MENTION_PATTERN, (...args: string[]) => {
    const [, prefix, token] = args
    const normalizedToken = token.replace(/[.,;:!?。！？、，；：]+$/, '')
    return `${prefix}<span class="preview-mention">${normalizedToken}</span>`
  })

  return marked.parse(highlighted) as string
}
