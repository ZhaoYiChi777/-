<template>
  <div class="smart-qa">
    <div class="qa-layout">
      <!-- 左侧：对话历史 -->
      <div :class="['session-sidebar', { collapsed: isHistoryCollapsed }]">
        <div class="session-panel">
          <div class="panel-header">
            <span class="panel-title">会话历史</span>
            <button class="new-chat-btn" @click="newSession">
              <span class="plus-icon">+</span> 新会话
            </button>
          </div>
          <div class="session-list">
            <div
              v-for="s in sessions"
              :key="s.session_id"
              :class="['session-item', { active: s.session_id === currentSessionId }]"
              @click="loadSession(s.session_id)"
            >
              <div class="session-info">
                <span class="chat-icon-bubble">💬</span>
                <span class="session-title" :title="s.title">{{ s.title }}</span>
              </div>
              <div class="session-actions">
                <el-tag size="small" type="info" class="session-count-tag">{{ s.msg_count }}条</el-tag>
                <el-icon class="delete-btn" @click.stop="deleteSession(s.session_id)"><Delete /></el-icon>
              </div>
            </div>
            <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="60" />
          </div>
        </div>
      </div>

      <!-- 右侧：对话区 -->
      <div class="chat-main">
        <div class="chat-panel">
          <div class="chat-header">
            <div class="chat-header-left">
              <el-button 
                class="toggle-sidebar-btn" 
                type="text" 
                @click="isHistoryCollapsed = !isHistoryCollapsed"
                :title="isHistoryCollapsed ? '展开历史会话' : '收起历史会话'"
              >
                <el-icon size="18">
                  <Expand v-if="isHistoryCollapsed" />
                  <Fold v-else />
                </el-icon>
              </el-button>
              <span class="chat-title">智能问答</span>
            </div>
            <div class="chat-header-right">
              <span class="chat-hint">语义检索 · 多文档交叉验证</span>
            </div>
          </div>

          <div class="chat-messages" ref="chatRef">
            <div class="chat-messages-inner">
              <TransitionGroup name="msg-slide" tag="div">
                <div 
                  v-for="(msg, i) in messages" 
                  :key="i" 
                  :class="['message', msg.role, { 'is-streaming': asking && i === messages.length - 1 && msg.role === 'assistant' }]"
                >
                  <!-- 用户消息 -->
                  <template v-if="msg.role === 'user'">
                    <div class="user-message-container">
                      <div class="user-bubble" v-html="formatText(msg.content, 'user')" />
                      <div class="user-avatar">
                        <div class="avatar-circle">您</div>
                      </div>
                    </div>
                  </template>

                  <!-- 助手消息 -->
                  <template v-else>
                    <div class="assistant-container">
                      <div class="assistant-avatar">
                        <div class="avatar-circle ai-avatar">AI</div>
                      </div>
                      
                      <div class="assistant-bubble">
                        <!-- Shimmer loading state when response is empty -->
                        <div v-if="asking && !msg.content" class="gemini-shimmer-loader">
                          <div class="shimmer-line w-1"></div>
                          <div class="shimmer-line w-2"></div>
                          <div class="shimmer-line w-3"></div>
                        </div>

                        <template v-else>
                          <!-- 文字回答 -->
                          <div class="answer-wrapper">
                            <div class="answer-text markdown-body" v-html="formatText(msg.content, 'assistant')" />
                            
                            <!-- Copy button/actions -->
                            <div v-if="!asking || i !== messages.length - 1" class="assistant-actions-bar">
                              <button class="action-icon-btn" @click="copyAnswer(msg.content)" title="复制回答">
                                <el-icon><DocumentCopy /></el-icon>
                              </button>
                            </div>
                          </div>

                          <!-- 置信度 -->
                          <div v-if="msg.confidence" class="confidence">
                            置信度: {{ (msg.confidence * 100).toFixed(0) }}%
                          </div>

                          <!-- 引用来源面板 -->
                          <div v-if="msg.sources && msg.sources.length > 0" class="references-panel">
                            <div class="references-header" @click="toggleRefs(i)">
                              <span class="references-icon">📑</span>
                              <span class="references-title">引用来源 ({{ msg.sources.length }})</span>
                              <span class="references-toggle">{{ expandedRefs.has(i) ? '收起' : '展开' }}</span>
                            </div>
                            <div class="references-list" v-if="expandedRefs.has(i)">
                              <div v-for="(src, j) in msg.sources" :key="j" class="reference-item">
                                <span class="ref-index">[{{ src.index || (j + 1) }}]</span>
                                <span :class="['ref-type-badge', 'ref-type-' + (src.media_type || 'text')]">
                                  {{ refTypeLabel(src.media_type || 'text') }}
                                </span>
                                <span class="ref-title" :title="src.source_origin || ''">
                                  {{ src.title }}
                                  <span v-if="src.source_name" style="color: #64748B; font-size: 11px; margin-left: 6px;">
                                    (来自: {{ src.source_name }})
                                  </span>
                                </span>
                              </div>
                            </div>
                            <!-- Collapsed preview: first 3 items -->
                            <div class="references-list collapsed-list" v-else>
                              <div v-for="(src, j) in msg.sources.slice(0, 3)" :key="j" class="reference-item">
                                <span class="ref-index">[{{ src.index || (j + 1) }}]</span>
                                <span :class="['ref-type-badge', 'ref-type-' + (src.media_type || 'text')]">
                                  {{ refTypeLabel(src.media_type || 'text') }}
                                </span>
                                <span class="ref-title" :title="src.source_origin || ''">
                                  {{ src.title }}
                                </span>
                              </div>
                              <div v-if="msg.sources.length > 3" class="references-more" @click.stop="toggleRefs(i)">
                                +{{ msg.sources.length - 3 }} 更多来源...
                              </div>
                            </div>
                          </div>

                          <!-- 图片区域 -->
                          <div v-if="getFilteredImages(msg).length > 0" class="images-section">
                            <div class="images-header" @click="toggleImages(i)">
                              <span class="images-icon">🖼️</span>
                              <span class="images-title">相关图片 ({{ getFilteredImages(msg).length }})</span>
                              <span class="images-toggle">{{ expandedImages.has(i) ? '收起' : '展开' }}</span>
                            </div>
                            <div class="images-content" v-if="expandedImages.has(i)">
                              <div class="images-grid">
                                <div v-for="(img, ii) in getFilteredImages(msg)" :key="'i'+ii" class="image-wrapper" @click="previewImage(img)">
                                  <span class="image-index">图{{ String(ii + 1).padStart(2, '0') }}</span>
                                  <img
                                    :src="getImageUrl(img.media_path || '')"
                                    class="result-image"
                                    :alt="img.title || '智能问答来源图片'"
                                    @error="handleImageError"
                                  />
                                  <div class="image-error" style="display:none; padding:20px; background:#f5f5f5; text-align:center; color:#999; border-radius:4px;">
                                    图片加载失败
                                  </div>
                                </div>
                              </div>
                            </div>
                          </div>
                        </template>
                      </div>
                    </div>
                  </template>
                </div>
              </TransitionGroup>

              <!-- Welcome screen -->
              <div v-if="messages.length === 0" class="welcome-wrapper">
                <h1 class="welcome-title">有什么我可以帮您的？</h1>
              </div>
            </div>
          </div>

          <!-- Bottom floating input bar -->
          <div class="chat-input-container">
            <div class="chat-input-pill">
              <!-- @mention badges above input -->
              <div v-if="mentionSelected.length > 0" class="mention-badges">
                <el-tag
                  v-for="doc in mentionSelected"
                  :key="doc.id"
                  closable
                  type="primary"
                  size="small"
                  @close="removeMention(doc.id)"
                  class="mention-tag"
                >
                  📎 {{ doc.title }}
                </el-tag>
              </div>

              <!-- Textarea input -->
              <textarea
                ref="inputRef"
                v-model="question"
                class="chat-textarea"
                :placeholder="'输入您的问题，输入 @ 提及文件...' + (asking ? '（正在思考...）' : '')"
                :disabled="asking"
                @keyup.enter.exact.prevent="askQuestion"
                @input="handleInputChange"
                @keydown.escape="mentionVisible = false"
                rows="1"
              />

              <!-- Action buttons inside pill -->
              <div class="pill-actions-row">
                <div class="pill-actions-left">
                  <!-- @ Mention shortcut trigger button -->
                  <button class="pill-btn" @click="triggerMentionInput" :disabled="asking" title="提及文件 (@)">
                    <el-icon><Document /></el-icon>
                    <span class="btn-text">提及文件</span>
                  </button>
                  
                  <!-- Stream Switch wrapper -->
                  <div class="stream-switch-wrapper">
                    <span class="stream-label">流式输出</span>
                    <el-switch v-model="streamEnabled" size="small" @change="onStreamToggle" />
                  </div>
                </div>
                
                <div class="pill-actions-right">
                  <!-- Send button -->
                  <button 
                    class="send-pill-btn" 
                    :class="{ active: question.trim() && !asking }"
                    :disabled="asking || !question.trim()"
                    @click="askQuestion"
                    title="发送问题"
                  >
                    <svg viewBox="0 0 24 24" class="send-arrow-icon">
                      <line x1="12" y1="19" x2="12" y2="5"></line>
                      <polyline points="5 12 12 5 19 12"></polyline>
                    </svg>
                  </button>
                </div>
              </div>

              <!-- @mention dropdown -->
              <div v-if="mentionVisible" class="mention-dropdown">
                <div
                  v-for="doc in mentionFilter"
                  :key="doc.id"
                  class="mention-item"
                  @click="selectMention(doc)"
                >
                  <el-icon class="mention-icon"><Document /></el-icon>
                  <span class="mention-title">{{ doc.title }}</span>
                  <el-tag size="small" type="info" class="mention-type">{{ doc.docType }}</el-tag>
                </div>
                <div v-if="mentionFilter.length === 0" class="mention-empty">没有匹配的文件</div>
              </div>
            </div>
            
            <div class="chat-input-disclaimer">
              智能问答结果由 AI 生成，请注意甄别重要信息。
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 图片预览弹窗 -->
    <el-dialog v-model="previewVisible" width="fit-content" top="5vh" :show-close="true" class="image-preview-dialog" append-to-body>
      <img :src="previewUrl" class="preview-full-image" alt="图片预览" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { Delete, DocumentCopy, Document, Fold, Expand } from '@element-plus/icons-vue'
import { askQuestion as askQuestionApi, askQuestionStream, getQAChatSessions, getQAChatSession, deleteQAChatSession, getMediaUrl, getSettings, updateSettings, getDocuments } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { renderPreviewMarkdown } from '../../utils/previewFormatting'

interface Message {
  role: 'user' | 'assistant'
  content: string
  confidence?: number
  sources?: Array<{ title: string; library: string; source_name: string; source_origin?: string; index?: number; media_type?: string }>
  tables?: Array<{ id: number; title: string; table_markdown: string; source_origin: string; source_name: string; score: number }>
  images?: Array<{ id: number; caption: string; media_path: string; title: string; source_origin: string; source_name: string; score: number }>
  _meta?: any  // 临时存储元数据
}

const messages = ref<Message[]>([])
const question = ref('')
const asking = ref(false)
const sessions = ref<any[]>([])
const currentSessionId = ref('session_' + Date.now())
const chatRef = ref<HTMLElement | null>(null)
const expandedRefs = ref<Set<number>>(new Set())
const expandedImages = ref<Set<number>>(new Set())
const previewVisible = ref(false)
const previewUrl = ref('')
const streamEnabled = ref(true) // 流式回答开关，默认开启
const isHistoryCollapsed = ref(false) // 历史会话收起状态

// @mention state
const mentionVisible = ref<boolean>(false)
const mentionQuery = ref<string>('')
const mentionDocs = ref<any[]>([])
const mentionSelected = ref<Array<{id: number, title: string}>>([])
const inputRef = ref<HTMLTextAreaElement | null>(null)

const mentionFilter = computed(() => {
  if (!mentionQuery.value) return mentionDocs.value.slice(0, 20)
  const q = mentionQuery.value.toLowerCase()
  return mentionDocs.value.filter(d => d.title?.toLowerCase().includes(q)).slice(0, 20)
})

function formatText(text: string, role: 'user' | 'assistant' = 'assistant'): string {
  if (!text) return ''
  if (role === 'user') {
    return text.replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>')
  }
  return renderPreviewMarkdown(text)
}

/** 过滤图片：排除已在表格中出现的图片（按ID去重），确保每个图表只出现一次 */
function getFilteredImages(msg: Message): Array<any> {
  if (!msg.images || msg.images.length === 0) return []
  const tableIds = new Set((msg.tables || []).map((t: any) => t.id))
  return msg.images.filter((img: any) => !tableIds.has(img.id))
}

/** 一键复制回答内容 */
async function copyAnswer(content: string) {
  if (!content) {
    ElMessage.warning('暂无可复制的内容')
    return
  }
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    // fallback for older browsers
    const textarea = document.createElement('textarea')
    textarea.value = content
    textarea.style.position = 'fixed'
    textarea.style.left = '-9999px'
    document.body.appendChild(textarea)
    textarea.select()
    try {
      document.execCommand('copy')
      ElMessage.success('已复制到剪贴板')
    } catch (err) {
      ElMessage.error('复制失败，请手动选择复制')
    }
    document.body.removeChild(textarea)
  }
}

/** 从media_path构建可访问的图片URL */
function getImageUrl(mediaPath: string): string {
  const url = getMediaUrl(mediaPath)
  console.log('[SmartQA] getImageUrl:', mediaPath, '->', url)
  return url
}

/** 图片加载失败处理 */
function handleImageError(e: Event) {
  const target = e.target as HTMLImageElement
  console.warn('[SmartQA] 图片加载失败:', target.src)
  target.style.display = 'none'
  const errorDiv = target.nextElementSibling as HTMLElement | null
  if (errorDiv) {
    errorDiv.style.display = 'block'
  }
}

/** 图片预览 */
function previewImage(img: any) {
  const url = getImageUrl(img.media_path)
  if (url) {
    previewUrl.value = url
    previewVisible.value = true
  }
}

/** 切换引用面板展开/折叠 */
function toggleRefs(index: number) {
  if (expandedRefs.value.has(index)) {
    expandedRefs.value.delete(index)
  } else {
    expandedRefs.value.add(index)
  }
}

/** 切换图片区域展开/折叠 */
function toggleImages(index: number) {
  if (expandedImages.value.has(index)) {
    expandedImages.value.delete(index)
  } else {
    expandedImages.value.add(index)
  }
}

/** 来源类型标签 */
function refTypeLabel(mediaType: string): string {
  return { image: '图表', table: '表格', text: '文本' }[mediaType] || '文本'
}

function askQuestion() {
  let q = question.value.trim()
  if (!q) return

  // Append referenced file names to the question
  if (mentionSelected.value.length > 0) {
    const titles = mentionSelected.value.map(m => m.title).join(', ')
    q += '\n\n[引用文件: ' + titles + ']'
    mentionSelected.value = []
  }

  asking.value = true
  messages.value.push({ role: 'user', content: q })
  question.value = ''
  
  // Reset input area height
  nextTick(() => {
    if (inputRef.value) {
      inputRef.value.style.height = 'auto'
    }
  })

  if (streamEnabled.value) {
    askWithStream(q)
  } else {
    askWithoutStream(q)
  }
}

function adjustTextareaHeight() {
  const textarea = inputRef.value
  if (textarea) {
    textarea.style.height = 'auto'
    textarea.style.height = Math.min(textarea.scrollHeight, 180) + 'px'
  }
}

function handleInputChange(e: Event) {
  adjustTextareaHeight()
  const target = e.target as HTMLTextAreaElement
  const val = target.value
  const cursorPos = target.selectionStart || 0
  // Detect @ trigger
  const textBeforeCursor = val.substring(0, cursorPos)
  const atMatch = textBeforeCursor.match(/@([^\s@]*)$/)
  if (atMatch) {
    mentionQuery.value = atMatch[1]
    mentionVisible.value = true
    if (mentionDocs.value.length === 0) {
      loadMentionDocs()
    }
  } else {
    mentionVisible.value = false
    mentionQuery.value = ''
  }
}

function selectMention(doc: any) {
  // Remove the @query text from input
  const textarea = inputRef.value
  if (textarea) {
    const cursorPos = textarea.selectionStart || 0
    const val = question.value
    const textBefore = val.substring(0, cursorPos)
    const atIndex = textBefore.lastIndexOf('@')
    if (atIndex >= 0) {
      question.value = val.substring(0, atIndex) + val.substring(cursorPos)
    }
  }
  // Add to selected mentions if not already present
  if (!mentionSelected.value.some(m => m.id === doc.id)) {
    mentionSelected.value.push({ id: doc.id, title: doc.title })
  }
  mentionVisible.value = false
  mentionQuery.value = ''
  nextTick(() => {
    inputRef.value?.focus()
    adjustTextareaHeight()
  })
}

function removeMention(docId: number) {
  mentionSelected.value = mentionSelected.value.filter(m => m.id !== docId)
}

async function loadMentionDocs() {
  try {
    const res = await getDocuments({ page: 1, pageSize: 100 })
    mentionDocs.value = res.data.items || []
  } catch (e) {
    console.error('Failed to load mention docs', e)
  }
}

function triggerMentionInput() {
  if (!question.value.endsWith('@')) {
    question.value += '@'
  }
  mentionVisible.value = true
  if (mentionDocs.value.length === 0) {
    loadMentionDocs()
  }
  nextTick(() => {
    inputRef.value?.focus()
    adjustTextareaHeight()
  })
}

/** 流式问答 */
function askWithStream(q: string) {
  const assistantMsg: Message = {
    role: 'assistant',
    content: '',
    confidence: 0,
    sources: [],
    tables: [],
    images: [],
  }
  messages.value.push(assistantMsg)
  const msgIndex = messages.value.length - 1

  askQuestionStream(
    { question: q, sessionId: currentSessionId.value },
    (meta) => {
      messages.value[msgIndex]._meta = meta
      if (meta.sessionId) {
        currentSessionId.value = meta.sessionId
      }
    },
    (text) => {
      messages.value[msgIndex].content += text
      nextTick().then(() => {
        if (chatRef.value) {
          chatRef.value.scrollTop = chatRef.value.scrollHeight
        }
      })
    },
    (result) => {
      messages.value[msgIndex].content = result.answer
      messages.value[msgIndex].confidence = result.confidence
      messages.value[msgIndex].sources = result.sources || []
      const meta = messages.value[msgIndex]._meta
      if (meta) {
        messages.value[msgIndex].tables = meta.tables || []
        messages.value[msgIndex].images = meta.images || []
        if (meta.images?.length > 0) expandedImages.value.add(msgIndex)
        if (result.sources && result.sources.length > 0) expandedRefs.value.add(msgIndex)
      }
      asking.value = false
      loadSessions()
    },
    (error) => {
      messages.value[msgIndex].content = '抱歉，系统暂时无法处理您的请求: ' + error
      asking.value = false
    }
  )
}

/** 非流式问答 */
async function askWithoutStream(q: string) {
  try {
    const res = await askQuestionApi({ question: q, sessionId: currentSessionId.value })
    const data = res.data
    messages.value.push({
      role: 'assistant',
      content: data.answer || '',
      confidence: data.confidence || 0,
      sources: data.sources || [],
      tables: data.tables || [],
      images: data.images || [],
    })
    const msgIndex = messages.value.length - 1
    if (data.sources?.length > 0) expandedRefs.value.add(msgIndex)
    if (data.images?.length > 0) expandedImages.value.add(msgIndex)
    asking.value = false
    loadSessions()
    nextTick().then(() => {
      if (chatRef.value) {
        chatRef.value.scrollTop = chatRef.value.scrollHeight
      }
    })
  } catch (e: any) {
    messages.value.push({
      role: 'assistant',
      content: '抱歉，系统暂时无法处理您的请求: ' + (e.message || '未知错误'),
      confidence: 0,
      sources: [],
    })
    asking.value = false
  }
}

/** 流式开关切换时持久化到后端 */
function onStreamToggle(val: boolean) {
  updateSettings({ qa_stream_enabled: val ? 'true' : 'false' }).catch(() => {})
}

function newSession() {
  currentSessionId.value = 'session_' + Date.now()
  messages.value = []
}

async function loadSessions() {
  try {
    const res = await getQAChatSessions()
    sessions.value = res.data || []
  } catch (e) { console.error(e) }
}

async function loadSession(sessionId: string) {
  currentSessionId.value = sessionId
  try {
    const res = await getQAChatSession(sessionId)
    messages.value = []
    for (const item of (res.data || [])) {
      messages.value.push({ role: 'user', content: item.question })
      messages.value.push({
        role: 'assistant',
        content: item.answer,
        confidence: item.confidence,
        sources: parseJsonArrayString(item.sources),
        images: parseJsonArrayString(item.images),
        tables: parseJsonArrayString(item.tables),
      })
    }
  } catch (e) { console.error(e) }
}

/** 解析数据库中的 JSON 数组字符串 */
function parseJsonArrayString(str: string | undefined): Array<any> {
  if (!str) return []
  try {
    const parsed = JSON.parse(str)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

async function deleteSession(sessionId: string) {
  try {
    await ElMessageBox.confirm('确定删除该会话及其所有记录？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteQAChatSession(sessionId)
    ElMessage.success('会话已删除')
    loadSessions()
    if (currentSessionId.value === sessionId) {
      newSession()
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + (e.message || '未知错误'))
    }
  }
}

onMounted(async () => {
  loadSessions()
  // 从后端加载流式开关配置
  try {
    const res = await getSettings()
    const settings = res.data || {}
    if (settings.qa_stream_enabled !== undefined) {
      streamEnabled.value = settings.qa_stream_enabled === 'true'
    }
  } catch (e) {
    console.warn('加载流式配置失败，使用默认值', e)
  }
})
</script>

<style scoped>
/* Outer Card Container */
.smart-qa {
  background: white;
  border-radius: 12px;
  padding: 0;
  height: calc(100vh - 120px);
  min-height: 600px;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.qa-layout {
  display: flex;
  height: 100%;
  width: 100%;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
}

/* --- SIDEBAR --- */
.session-sidebar {
  width: 280px;
  min-width: 280px;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease;
  overflow: hidden;
  flex-shrink: 0;
  height: 100%;
  border-right: 1px solid #e2e8f0;
  background-color: #f0f4f9; /* Gemini Sidebar color */
}

.session-sidebar.collapsed {
  width: 0;
  min-width: 0;
  border-right: none;
  opacity: 0;
  pointer-events: none;
}

.session-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 280px;
  box-sizing: border-box;
}

.panel-header {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.panel-title {
  font-weight: 600;
  font-size: 14px;
  color: #1f1f1f;
  margin-top: 4px;
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background-color: #d3e3fd; /* Google light blue */
  color: #041e49;
  border: none;
  border-radius: 20px;
  padding: 10px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  width: 100%;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.new-chat-btn:hover {
  background-color: #c2d8fc;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
}

.plus-icon {
  font-size: 16px;
  font-weight: 700;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-radius: 20px; /* Pill-shaped items like Gemini */
  cursor: pointer;
  margin-bottom: 4px;
  position: relative;
  transition: background-color 0.2s, color 0.2s;
  color: #444746;
}

.session-item:hover {
  background-color: rgba(0, 0, 0, 0.04);
}

.session-item.active {
  background-color: #d3e3fd;
  color: #041e49;
  font-weight: 600;
}

.session-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.chat-icon-bubble {
  font-size: 14px;
  flex-shrink: 0;
}

.session-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
  flex-shrink: 0;
}

.session-count-tag {
  border-radius: 10px;
  background: rgba(0,0,0,0.05) !important;
  color: #5f6368 !important;
  border: none !important;
}

.delete-btn {
  cursor: pointer;
  color: #94A3B8;
  font-size: 14px;
  transition: color 0.2s;
  opacity: 0;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  color: #E53E3E;
}


/* --- CHAT MAIN AREA --- */
.chat-main {
  flex: 1;
  min-width: 0;
  height: 100%;
}

.chat-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: white;
}

.chat-header {
  height: 56px;
  border-bottom: 1px solid #f1f3f4;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  flex-shrink: 0;
}

.chat-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toggle-sidebar-btn {
  padding: 0;
  min-height: unset;
  color: #5f6368;
  cursor: pointer;
  margin-right: 4px;
}

.toggle-sidebar-btn:hover {
  color: #1a73e8;
}

.chat-title {
  font-weight: 600;
  color: #1f1f1f;
  font-size: 15px;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.chat-hint {
  font-size: 12px;
  color: #747775;
}


/* --- CHAT MESSAGES PANEL --- */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 12px;
  box-sizing: border-box;
}

.chat-messages-inner {
  max-width: 820px;
  width: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.message {
  margin-bottom: 28px;
  display: flex;
  width: 100%;
}

.message.user {
  justify-content: flex-end;
}

.message.assistant {
  justify-content: flex-start;
}

/* User Message Layout */
.user-message-container {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  max-width: 85%;
  margin-left: auto;
}

.user-bubble {
  padding: 12px 18px;
  border-radius: 20px;
  background: #f0f4f9;
  color: #1f1f1f;
  font-size: 14px;
  line-height: 1.6;
  border: 1px solid #e1e4e8;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.user-avatar {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  margin-top: 4px;
}

.avatar-circle {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #1a73e8;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(26, 115, 232, 0.25);
}

/* Assistant Message Layout */
.assistant-container {
  display: flex;
  gap: 12px;
  width: 100%;
  align-items: flex-start;
}

.assistant-avatar {
  flex-shrink: 0;
  margin-top: 4px;
}

.ai-avatar {
  background: #f0f4f9;
  color: #1a73e8;
  border: 1px solid #d3e3fd;
  box-shadow: none;
  font-size: 11px;
}

.assistant-bubble {
  flex: 1;
  min-width: 0;
}

.answer-wrapper {
  position: relative;
  width: 100%;
}

.answer-text {
  background: transparent;
  padding: 4px 0 0;
  font-size: 15px;
  line-height: 1.8;
  color: #1f1f1f;
}

/* Streaming pulse dot */
.message.is-streaming :deep(.markdown-body > p:last-child::after),
.message.is-streaming :deep(.markdown-body > *:last-child::after) {
  content: '●';
  font-size: 10px;
  display: inline-block;
  margin-left: 6px;
  color: #1a73e8;
  vertical-align: middle;
  animation: pulse-dot 1.2s infinite ease-in-out;
}

@keyframes pulse-dot {
  0%, 100% { transform: scale(0.8); opacity: 0.3; }
  50% { transform: scale(1.2); opacity: 1; }
}

/* Actions bar below bot answer */
.assistant-actions-bar {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  opacity: 0;
  transition: opacity 0.2s;
}

.message:hover .assistant-actions-bar {
  opacity: 1;
}

.action-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: #5f6368;
  border-radius: 50%;
  cursor: pointer;
  transition: background 0.15s;
}

.action-icon-btn:hover {
  background: #f1f3f4;
  color: #1f1f1f;
}

.confidence {
  font-size: 11px;
  color: #747775;
  margin-top: 8px;
}


/* --- SHIMMER LOADER --- */
.gemini-shimmer-loader {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  margin-top: 8px;
}

.shimmer-line {
  height: 16px;
  border-radius: 8px;
  background: linear-gradient(90deg, #f0f4f9 25%, #e1e4e8 50%, #f0f4f9 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite linear;
}

.shimmer-line.w-1 { width: 45%; }
.shimmer-line.w-2 { width: 85%; }
.shimmer-line.w-3 { width: 65%; }

@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}


/* --- WELCOME SCREEN --- */
.welcome-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px 40px;
  width: 100%;
  box-sizing: border-box;
}

.welcome-title {
  font-size: 26px;
  font-weight: 600;
  color: #1f1f1f;
  margin: 0;
  text-align: center;
  letter-spacing: -0.5px;
}


/* --- FLOATING INPUT AREA --- */
.chat-input-container {
  padding: 16px 24px 20px;
  background: white;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  box-sizing: border-box;
  flex-shrink: 0;
}

.chat-input-pill {
  width: 100%;
  max-width: 760px;
  background: #f0f4f9;
  border-radius: 28px;
  padding: 14px 18px 8px;
  box-sizing: border-box;
  position: relative;
  transition: background-color 0.2s, box-shadow 0.2s, border-color 0.2s;
  border: 1px solid transparent;
}

.chat-input-pill:focus-within {
  background: white;
  border-color: #d3e3fd;
  box-shadow: 0 4px 18px rgba(0,0,0,0.08), 0 1px 4px rgba(0,0,0,0.04);
}

.chat-textarea {
  width: 100%;
  border: none;
  background: transparent;
  outline: none;
  resize: none;
  font-family: inherit;
  font-size: 15px;
  line-5height: 1.5;
  color: #1f1f1f;
  padding: 4px 8px;
  box-sizing: border-box;
  min-height: 36px;
  max-height: 180px;
}

.chat-textarea::placeholder {
  color: #747775;
}

.pill-actions-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  border-top: 1px solid rgba(0, 0, 0, 0.04);
  padding-top: 8px;
}

.pill-actions-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.pill-btn {
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #444746;
  font-size: 13px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 16px;
  transition: background-color 0.15s;
}

.pill-btn:hover {
  background: rgba(0,0,0,0.05);
}

.pill-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.btn-text {
  font-weight: 500;
}

.stream-switch-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stream-label {
  font-size: 12px;
  color: #444746;
}

.pill-actions-right {
  display: flex;
  align-items: center;
}

.send-pill-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #cbd5e1;
  color: white;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: not-allowed;
  transition: all 0.2s ease;
}

.send-pill-btn.active {
  background: #1a73e8;
  color: white;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(26,115,232,0.3);
}

.send-pill-btn.active:hover {
  background: #1557b0;
  transform: scale(1.05);
}

.send-arrow-icon {
  width: 18px;
  height: 18px;
  stroke: currentColor;
  stroke-width: 2.5;
  fill: none;
}

.chat-input-disclaimer {
  font-size: 11px;
  color: #747775;
  margin-top: 8px;
  text-align: center;
}


/* --- MENTION DROPDOWN --- */
.mention-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  padding: 0 8px;
}

.mention-tag {
  border-radius: 12px;
  padding: 4px 10px;
  background-color: #e8f0fe !important;
  border-color: #d3e3fd !important;
  color: #1a73e8 !important;
  font-weight: 500;
}

.mention-dropdown {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 16px;
  right: 16px;
  background: white;
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
  max-height: 240px;
  overflow-y: auto;
  z-index: 1000;
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #F1F5F9;
}

.mention-item:last-child {
  border-bottom: none;
}

.mention-item:hover {
  background: #EFF6FF;
}

.mention-icon {
  color: #1a73e8;
  font-size: 14px;
  flex-shrink: 0;
}

.mention-title {
  flex: 1;
  font-size: 13px;
  color: #1f1f1f;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mention-type {
  flex-shrink: 0;
  font-size: 11px;
}

.mention-empty {
  padding: 16px;
  text-align: center;
  color: #94A3B8;
  font-size: 13px;
}


/* --- REFERENCES AND IMAGES CARDS --- */
.references-panel {
  margin-top: 14px;
  border: 1px solid #e1e4e8;
  border-radius: 12px;
  background: #f8fafc;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}

.references-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}

.references-header:hover {
  background: #f1f5f9;
}

.references-icon {
  font-size: 14px;
}

.references-title {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
  flex: 1;
}

.references-toggle {
  font-size: 12px;
  color: #64748b;
}

.references-list {
  padding: 8px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-top: 1px solid #f1f5f9;
}

.reference-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
  transition: background 0.1s;
}

.reference-item:hover {
  background: #EFF6FF;
}

.ref-index {
  font-size: 10px;
  color: #94A3B8;
  font-weight: 600;
  min-width: 22px;
  text-align: right;
}

.ref-type-badge {
  font-size: 9px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
}

.ref-type-image { background: #DBEAFE; color: #2563EB; }
.ref-type-table { background: #FEF3C7; color: #D97706; }
.ref-type-text { background: #F1F5F9; color: #64748B; }

.ref-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
}

.references-more {
  padding: 4px 8px;
  font-size: 12px;
  color: #1a73e8;
  cursor: pointer;
  font-weight: 500;
}

.references-more:hover {
  text-decoration: underline;
}

.images-section {
  margin-top: 14px;
}

.images-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
  border: 1px solid #e1e4e8;
  border-radius: 12px;
  background: #f8fafc;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}

.images-header:hover {
  background: #f1f5f9;
}

.images-title {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
  flex: 1;
}

.images-toggle {
  font-size: 12px;
  color: #64748b;
}

.images-content {
  margin-top: 10px;
  padding: 4px;
}

.images-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.image-wrapper {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e1e4e8;
  transition: transform 0.2s, box-shadow 0.2s;
}

.image-wrapper:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.image-index {
  position: absolute;
  top: 6px;
  left: 6px;
  background: rgba(0,0,0,0.6);
  color: white;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  z-index: 1;
}

.result-image {
  max-height: 180px;
  object-fit: cover;
  display: block;
}

.preview-full-image {
  max-width: 85vw;
  max-height: 85vh;
  object-fit: contain;
  display: block;
  margin: 0 auto;
}

.image-preview-dialog :deep(.el-dialog__body) {
  padding: 10px;
}


/* --- MARKDOWN BODY STYLE CUSTOMIZATION --- */
.markdown-body {
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 14px 0 8px;
  font-weight: 600;
  line-height: 1.4;
  color: #1f1f1f;
}

.markdown-body :deep(h1) { font-size: 1.25em; }
.markdown-body :deep(h2) { font-size: 1.15em; }
.markdown-body :deep(h3) { font-size: 1.05em; }
.markdown-body :deep(p) { margin: 6px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { margin: 6px 0; padding-left: 20px; }
.markdown-body :deep(li) { margin: 3px 0; }

.markdown-body :deep(code) {
  background: rgba(0,0,0,0.05);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: #b06000;
}

.markdown-body :deep(pre) {
  background: #1e1e24;
  color: #f3f3f3;
  padding: 14px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
  box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: inherit;
}

.markdown-body :deep(blockquote) {
  border-left: 4px solid #1a73e8;
  padding-left: 14px;
  margin: 12px 0;
  color: #5f6368;
  background: #f8fafc;
  padding-top: 4px;
  padding-bottom: 4px;
  border-top-right-radius: 4px;
  border-bottom-right-radius: 4px;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 14px 0;
  font-size: 13px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e1e4e8;
}

.markdown-body :deep(table th), .markdown-body :deep(table td) {
  padding: 10px 12px;
  text-align: left;
}

.markdown-body :deep(table th) {
  background: #f0f4f9;
  font-weight: 600;
  color: #1f1f1f;
  border-bottom: 2px solid #e1e4e8;
}

.markdown-body :deep(table td) {
  border-bottom: 1px solid #e1e4e8;
  color: #444746;
}

.markdown-body :deep(table tr:last-child td) {
  border-bottom: none;
}

.markdown-body :deep(table tr:nth-child(even)) {
  background: #f8fafc;
}

.markdown-body :deep(strong) { font-weight: 600; color: #1f1f1f; }
.markdown-body :deep(em) { font-style: italic; }
.markdown-body :deep(a) { color: #1a73e8; text-decoration: none; }
.markdown-body :deep(a:hover) { text-decoration: underline; }
.markdown-body :deep(hr) { border: none; border-top: 1px solid #e1e4e8; margin: 16px 0; }
.markdown-body :deep(img) { max-width: 100%; border-radius: 8px; }


/* Custom Scrollbars */
.chat-messages::-webkit-scrollbar,
.session-list::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.chat-messages::-webkit-scrollbar-track,
.session-list::-webkit-scrollbar-track {
  background: transparent;
}
.chat-messages::-webkit-scrollbar-thumb,
.session-list::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 3px;
}
.chat-messages::-webkit-scrollbar-thumb:hover,
.session-list::-webkit-scrollbar-thumb:hover {
  background: #94a3b8;
}


/* --- TRANSITION ANIMATIONS --- */
.msg-slide-enter-active {
  transition: all 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.msg-slide-leave-active {
  transition: all 0.2s ease-in;
}

.msg-slide-enter-from {
  opacity: 0;
  transform: translateY(20px);
}

.msg-slide-enter-to {
  opacity: 1;
  transform: translateY(0);
}

.msg-slide-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}
</style>
