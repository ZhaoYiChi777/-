import axios from 'axios'
import { reportError } from '../utils/errorBus'

// 检测是否在 Tauri 桌面环境中运行
const isTauri = '__TAURI_INTERNALS__' in window

// Tauri 环境下直接连接后端，非 Tauri 环境通过 Vite proxy
const api = axios.create({
  baseURL: isTauri ? 'http://localhost:8080/api' : '/api',
  timeout: 30000,
})

// 当前项目ID管理（参考 llm_wiki 的数据隔离机制）
let currentProjectId: number | null = null

export function setCurrentProjectId(id: number | null) {
  currentProjectId = id
  if (id) {
    localStorage.setItem('currentProjectId', String(id))
  } else {
    localStorage.removeItem('currentProjectId')
  }
}

export function getCurrentProjectId(): number | null {
  if (currentProjectId !== null) return currentProjectId
  const stored = localStorage.getItem('currentProjectId')
  return stored ? parseInt(stored) : null
}

// 请求拦截器：自动添加 X-Project-Id 头并禁用缓存
api.interceptors.request.use((config) => {
  const projectId = getCurrentProjectId()
  if (projectId) {
    config.headers['X-Project-Id'] = String(projectId)
  }
  return config
})

// 响应拦截器：捕获网络错误和 5xx 服务端错误，上报到 errorBus
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (!error.response) {
      // 网络错误或请求超时
      reportError(error, `网络请求失败: ${error.config?.url || 'unknown'}`)
    } else if (error.response.status >= 500) {
      // 服务端错误
      reportError(error, `服务端错误 ${error.response.status}: ${error.config?.url || 'unknown'}`)
    }
    return Promise.reject(error)
  }
)

// 用于轮询的请求头（禁用缓存）
export const noCacheHeaders = {
  headers: { 'Cache-Control': 'no-cache', 'Pragma': 'no-cache' }
}

// Projects (项目隔离 - 参考 llm_wiki WikiProject)
export const getProjects = () => api.get('/projects')
export const createProject = (data: { name: string; description?: string }) => api.post('/projects/', data)
export const deleteProject = (id: number) => api.delete(`/projects/${id}`)
export const getProjectDetail = (id: number) => api.get(`/projects/${id}/detail`)

// Dashboard
export const getDashboardStats = () => api.get('/dashboard/stats')
export const getLatestDocuments = (limit = 5) => api.get('/dashboard/latest-documents', { params: { limit } })
export const getActiveProjects = (limit = 5) => api.get('/dashboard/active-projects', { params: { limit } })

// Documents
export const getDocuments = (params: Record<string, any>) => api.get('/documents', { params })
export const getDocument = (id: number) => api.get(`/documents/${id}`)
export const createDocument = (data: any) => api.post('/documents/', data)
export const updateDocument = (id: number, data: any) => api.put(`/documents/${id}`, data)
export const deleteDocument = (id: number) => api.delete(`/documents/${id}`)
export const reExtractDocument = (id: number) =>
  api.post(`/documents/${id}/re-extract`)

// Reports
export const getReports = (params: Record<string, any>) => api.get('/reports', { params })
export const getReport = (id: number) => api.get(`/reports/${id}`)
export const createReport = (data: any) => api.post('/reports/', data)
export const deleteReport = (id: number) => api.delete(`/reports/${id}`)

// QA
export const getQAList = (params: Record<string, any>) => api.get('/qa', { params })
export const getQAStats = () => api.get('/qa/stats')
export const getQASessions = () => api.get('/qa/sessions')
export const getQASession = (sessionId: string) => api.get(`/qa/session/${sessionId}`)
export const deleteQASession = (sessionId: string) => api.delete(`/qa/session/${sessionId}`)
export const createQA = (data: any) => api.post('/qa/', data)
export const deleteQA = (id: number) => api.delete(`/qa/${id}`)

// Analysis
export const getAnalysisList = (params: Record<string, any>) => api.get('/analysis', { params })
export const getAnalysisStats = () => api.get('/analysis/stats')
export const getAnalysis = (id: number) => api.get(`/analysis/${id}`)
export const createAnalysis = (data: any) => api.post('/analysis/', data)
export const deleteAnalysis = (id: number) => api.delete(`/analysis/${id}`)

// Risks
export const getRiskList = (params: Record<string, any>) => api.get('/risks', { params })
export const getRiskStats = () => api.get('/risks/stats')
export const createRisk = (data: any) => api.post('/risks/', data)
export const deleteRisk = (id: number) => api.delete(`/risks/${id}`)

// Decisions
export const getDecisionList = (params: Record<string, any>) => api.get('/decisions', { params })
export const createDecision = (data: any) => api.post('/decisions/', data)
export const deleteDecision = (id: number) => api.delete(`/decisions/${id}`)

// KG
export const getKGNodes = (params?: { page?: number; pageSize?: number }) => 
  api.get('/kg/nodes', { params })
export const getKGEdges = (params?: { page?: number; pageSize?: number }) => 
  api.get('/kg/edges', { params })
export const getKGGraph = () => api.get('/kg/graph')
export const getKGInsights = () => api.get('/kg/insights')
export const buildKGGraph = () => api.post('/kg/build')

// Settings
export const getSettings = () => api.get('/settings')
export const updateSettings = (data: Record<string, string>) => api.put('/settings/', data)

// Search Config
export const getSearchConfig = () => api.get('/search-config')
export const saveSearchConfig = (data: Record<string, string>) => api.put('/search-config', data)
export const testSearchConfig = (query?: string) => api.post('/search-config/test', { query: query || 'Spring Boot' })

// Upload
// 注意：不要手动设置 Content-Type: multipart/form-data，浏览器会自动添加正确的 boundary 参数
export const uploadFile = (formData: FormData, url: string = '/upload/') => api.post(url, formData, {
  timeout: 120000, // 2分钟超时，适合大文件上传
})
export const uploadFromUrl = (data: { url: string; title?: string; categoryL1?: string; docType?: string; sourceOrigin?: string }) =>
  api.post('/upload/from-url', null, { params: data, timeout: 120000 })
export const checkFileHash = (formData: FormData) => api.post('/upload/check-hash', formData, {
  timeout: 30000,
})
export const getFileTypes = () => api.get('/upload/file-types')
export const getLibraries = () => api.get('/upload/libraries')
export const analyzeDocument = (formData: FormData) => api.post('/upload/analyze', formData, {
  timeout: 120000,
})
export const getUploadTaskStatus = (docId: number) =>
  api.get(`/upload/task/${docId}`)
export const getAllUploadTasks = () =>
  api.get('/upload/tasks')
export const cancelUploadTask = (docId: number) =>
  api.post(`/upload/task/${docId}/cancel`)

// Search
export const unifiedSearch = (params: Record<string, any>) => api.get('/search', { params })
export const getFullStats = () => api.get('/search/stats/full')

// Knowledge Entries (知识词条 - 前台研究报告Tab核心)
export const getKnowledgeEntries = (params: Record<string, any>) => api.get('/knowledge-entries', { params })
export const getKnowledgeEntry = (id: number) => api.get(`/knowledge-entries/${id}`)
export const createKnowledgeEntry = (data: any) => api.post('/knowledge-entries/', data)
export const updateKnowledgeEntry = (id: number, data: any) => api.put(`/knowledge-entries/${id}`, data)
export const reviewKnowledgeEntry = (id: number, status: string, reviewer?: string) =>
  api.put(`/knowledge-entries/${id}/review`, null, { params: { status, reviewer } })
export const batchReviewEntries = (ids: number[], status: string, reviewer?: string) =>
  api.put('/knowledge-entries/batch-review', { ids, status, reviewer })
export const deleteKnowledgeEntry = (id: number) => api.delete(`/knowledge-entries/${id}`)
export const updateTableMarkdown = (id: number, tableMarkdown: string) =>
  api.put(`/knowledge-entries/${id}/table-markdown`, { tableMarkdown })
export const getEntryStatsByLibrary = () => api.get('/knowledge-entries/stats/by-library')

// LLM Config (LLM配置 - 后台系统配置)
export const getLlmConfigs = () => api.get('/llm-configs')
export const getActiveLlmConfig = () => api.get('/llm-configs/active')
export const getActiveEmbeddingConfig = () => api.get('/llm-configs/active-embedding')
export const getLlmConfig = (id: number) => api.get(`/llm-configs/${id}`)
export const createLlmConfig = (data: any) => api.post('/llm-configs/', data)
export const updateLlmConfig = (id: number, data: any) => api.put(`/llm-configs/${id}`, data)
export const toggleLlmConfig = (id: number) => api.put(`/llm-configs/${id}/toggle`)
export const deleteLlmConfig = (id: number) => api.delete(`/llm-configs/${id}`)
export const testLlmConnection = (data: any) => api.post('/llm-configs/test', data)

// Vector Search (向量搜索 - FAISS IndexFlatIP)
export const getVectorStats = () => api.get('/vector/stats')
export const rebuildVectorIndex = () => api.post('/vector/rebuild')
export const vectorSearch = (data: { query: string; topK?: number }) => api.post('/vector/search', data)

// QA Chat (智能问答 - Graph-RAG风格)
// 使用更长超时时间（5分钟），因为涉及：embedding查询 + 向量搜索 + LLM生成
export const askQuestion = (data: { question: string; sessionId?: string }) =>
  api.post('/qa-chat/ask', data, { timeout: 300000 })

// 流式问答接口（SSE）
export function askQuestionStream(
  data: { question: string; sessionId?: string },
  onMeta: (meta: any) => void,
  onDelta: (text: string) => void,
  onDone: (result: { answer: string; confidence: number; sources?: Array<any>; tables?: Array<any>; images?: Array<any> }) => void,
  onError?: (error: string) => void
) {
  const baseUrl = isTauri ? 'http://localhost:8080/api' : '/api'
  const url = `${baseUrl}/qa-chat/ask/stream`

  // 构建请求头：必须手动添加 X-Project-Id，因为 fetch 不走 axios 拦截器
  const fetchHeaders: Record<string, string> = { 'Content-Type': 'application/json' }
  const projectId = getCurrentProjectId()
  if (projectId) {
    fetchHeaders['X-Project-Id'] = String(projectId)
  }

  fetch(url, {
    method: 'POST',
    headers: fetchHeaders,
    body: JSON.stringify(data),
  }).then(response => {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const reader = response.body?.getReader()
    if (!reader) throw new Error('No reader')
    const actualReader = reader

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''

    function read() {
      actualReader.read().then(({ done, value }) => {
        if (done) return

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const trimmed = line.trim()
          
          // 空行表示事件结束，重置事件类型
          if (trimmed === '') {
            currentEvent = ''
            continue
          }
          
          // 解析 event: 行
          if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.substring(6).trim()
            continue
          }
          
          // 解析 data: 行
          if (trimmed.startsWith('data:')) {
            const dataStr = trimmed.substring(5).trim()
            try {
              const eventData = JSON.parse(dataStr)
              
              // 优先使用事件类型分发
              if (currentEvent === 'meta' || eventData.sessionId !== undefined) {
                onMeta(eventData)
              } else if (currentEvent === 'delta' || eventData.text !== undefined) {
                onDelta(eventData.text)
              } else if (currentEvent === 'done' || eventData.answer !== undefined) {
                onDone(eventData)
              } else if (currentEvent === 'error' || eventData.message !== undefined) {
                onError?.(eventData.message)
              }
            } catch (e) {
              console.warn('Failed to parse SSE data:', dataStr, e)
            }
          }
        }
        read()
      })
    }
    read()
  }).catch(err => {
    onError?.(err.message || 'Unknown error')
  })
}

export const getQAChatSessions = () => api.get('/qa-chat/sessions')
export const getQAChatSession = (sessionId: string) => api.get(`/qa-chat/session/${sessionId}`)
export const deleteQAChatSession = (sessionId: string) => api.delete(`/qa-chat/session/${sessionId}`)

// Deep Research (深度研究)
export const getDeepResearches = (params: Record<string, any>) => api.get('/deep-researches', { params })
export const getDeepResearch = (id: number) => api.get(`/deep-researches/${id}`)
export const createDeepResearch = (data: { topic: string }) => api.post('/deep-researches', data)
export const cancelDeepResearch = (id: number) => api.put(`/deep-researches/${id}/cancel`)
export const deleteDeepResearch = (id: number) => api.delete(`/deep-researches/${id}`)

// Sources (来源管理 - 参考 llm_wiki source-lifecycle)
export const getSourceTree = () => api.get('/sources/tree')
export const importSourceFolder = (data: { folderPath: string; categoryL1?: string; categoryL2?: string; sourceOrigin?: string }) =>
  api.post('/sources/import-folder', null, { params: data })
export const importSourceFile = (formData: FormData) =>
  api.post('/sources/import-file', formData, {
    timeout: 120000,
  })
export const deleteSource = (id: number) => api.delete(`/sources/${id}`)
export const refreshSources = () => api.post('/sources/refresh')

// Media (媒体文件访问)
/**
 * 根据 knowledge entry 的 mediaPath 构建可访问的图片 URL
 * mediaPath 格式: ../uploads/media/{docId}/{index}.{ext} 或绝对路径
 */
export function getMediaUrl(mediaPath: string): string {
  if (!mediaPath) return ''
  const parts = mediaPath.replace(/\\/g, '/').split('/')
  const filename = parts.pop()
  const docId = parts.pop()
  if (!docId || !filename) return ''
  const base = isTauri ? 'http://localhost:8080/api' : '/api'
  return `${base}/media/${docId}/${filename}`
}

export function getPdfCoverUrl(docId: number): string {
  const base = isTauri ? 'http://localhost:8080/api' : '/api'
  return `${base}/media/pdf-cover/${docId}`
}

export function getDocFileUrl(docId: number): string {
  const base = isTauri ? 'http://localhost:8080/api' : '/api'
  return `${base}/media/doc-file/${docId}`
}

/** OCR 识别图片中的表格 */
export const ocrTableImage = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/media/ocr-table', formData, {
    timeout: 120000,
  })
}

/** 合并多个表格条目为一个 Markdown 表格 */
export const mergeTableEntries = (data: { entryIds: number[]; title?: string }) =>
  api.post('/media/merge-tables', data, { timeout: 120000 })

/** 将已有图片条目通过 OCR 转为表格条目 */
export const ocrExistingEntries = (data: { entryIds: number[] }) =>
  api.post('/media/ocr-existing', data, { timeout: 300000 })

/** 批量重新处理失败的图片条目（重新生成VLM描述） */
export const reprocessImages = (data?: { entryIds?: number[] }) =>
  api.post('/media/reprocess-images', data || {}, { timeout: 600000 })

export default api
