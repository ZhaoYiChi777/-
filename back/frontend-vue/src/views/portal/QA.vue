<template>
  <div class="qa-page">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card>
          <template #header><span>智能问答</span></template>
          <div class="chat-area">
            <div v-for="(msg, i) in messages" :key="i" :class="['chat-bubble', msg.role]">
              <div class="bubble-content">{{ msg.content }}</div>
              <div v-if="msg.confidence" class="confidence">置信度: {{ (msg.confidence * 100).toFixed(0) }}%</div>
            </div>
            <el-empty v-if="messages.length === 0" description="输入问题开始对话" />
          </div>
          <div class="input-area">
            <el-input v-model="question" placeholder="输入您的问题" @keyup.enter="askQuestion" />
            <el-button type="primary" @click="askQuestion" :loading="asking" style="margin-left: 10px;">提问</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>历史会话</span>
              <el-button size="small" text type="primary" @click="newSession()">+ 新对话</el-button>
            </div>
          </template>
          <div v-for="s in sessions" :key="s.session_id" class="session-item" @click="loadSession(s.session_id)">
            <span>{{ s.title }}</span>
            <div style="display: flex; align-items: center;">
              <el-tag size="small">{{ s.msg_count }}条</el-tag>
              <el-button size="small" text type="danger" @click.stop="deleteSession(s.session_id)" style="margin-left: 8px;">
                <el-icon :size="14"><Delete /></el-icon>
              </el-button>
            </div>
          </div>
          <el-empty v-if="sessions.length === 0" description="暂无历史会话" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { askQuestion as askAPI, getQAChatSessions, getQAChatSession, deleteQAChatSession } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'

interface Message { role: 'user' | 'assistant'; content: string; confidence?: number }

const messages = ref<Message[]>([])
const question = ref('')
const asking = ref(false)
const sessions = ref<any[]>([])
const sessionId = ref('session_' + Date.now())

async function askQuestion() {
  if (!question.value.trim()) return
  asking.value = true
  const q = question.value
  messages.value.push({ role: 'user', content: q })
  question.value = ''
  try {
    const res = await askAPI({ question: q, sessionId: sessionId.value })
    messages.value.push({ role: 'assistant', content: res.data.answer, confidence: res.data.confidence })
    loadSessions()
  } catch (e: any) {
    messages.value.push({ role: 'assistant', content: '抱歉，系统暂时无法处理您的请求: ' + (e.message || '未知错误') })
  }
  asking.value = false
}

function newSession() {
  sessionId.value = 'session_' + Date.now()
  messages.value = []
}

async function loadSessions() {
  try {
    const res = await getQAChatSessions()
    sessions.value = res.data || []
  } catch (e) { console.error(e) }
}

async function loadSession(sid: string) {
  sessionId.value = sid
  try {
    const res = await getQAChatSession(sid)
    messages.value = []
    for (const item of (res.data || [])) {
      messages.value.push({ role: 'user', content: item.question })
      messages.value.push({ role: 'assistant', content: item.answer, confidence: item.confidence })
    }
  } catch (e) { console.error(e) }
}

async function deleteSession(sid: string) {
  try {
    await ElMessageBox.confirm('确定删除该会话及其所有记录？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteQAChatSession(sid)
    ElMessage.success('会话已删除')
    loadSessions()
    if (sessionId.value === sid) {
      newSession()
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + (e.message || '未知错误'))
    }
  }
}

onMounted(loadSessions)
</script>

<style scoped>
.qa-page { padding: 20px; }
.chat-area { max-height: 500px; overflow-y: auto; margin-bottom: 16px; }
.chat-bubble { margin-bottom: 12px; display: flex; }
.chat-bubble.user { justify-content: flex-end; }
.bubble-content { max-width: 70%; padding: 10px 16px; border-radius: 12px; }
.user .bubble-content { background: #409eff; color: white; }
.assistant .bubble-content { background: #f4f4f5; }
.confidence { font-size: 12px; color: #909399; margin-top: 4px; }
.input-area { display: flex; }
.session-item { display: flex; justify-content: space-between; padding: 8px; cursor: pointer; border-bottom: 1px solid #ebeef5; }
.session-item:hover { background: #f5f7fa; }
</style>
