<template>
  <div class="deep-research">
    <el-row :gutter="16">
      <!-- 左侧：新建研究 + 任务列表 -->
      <el-col :span="8">
        <div class="panel">
          <div class="panel-header">
            <span>深度研究</span>
            <el-button size="small" @click="showNewResearch = true">+ 新建研究</el-button>
          </div>

          <div class="task-list">
            <div
              v-for="task in tasks"
              :key="task.id"
              :class="['task-item', { active: selectedTask?.id === task.id }]"
              @click="selectTask(task)"
            >
              <div class="task-title">{{ task.topic }}</div>
              <div class="task-meta">
                <el-tag :type="taskStatusType(task.status)" size="small">{{ taskStatusLabel(task.status) }}</el-tag>
                <div class="task-actions">
                  <span class="task-time">{{ task.created_at }}</span>
                  <el-icon class="delete-btn" @click.stop="deleteTask(task.id)"><Delete /></el-icon>
                </div>
              </div>
              <el-progress
                v-if="task.status === 'running' || task.status === 'queued'"
                :percentage="task.progress || 0"
                :stroke-width="6"
                style="margin-top: 6px;"
              />
            </div>
            <el-empty v-if="tasks.length === 0" description="暂无研究任务" :image-size="60" />
          </div>
        </div>
      </el-col>

      <!-- 右侧：研究结果 -->
      <el-col :span="16">
        <div class="result-panel research-result">
          <div v-if="selectedTask">
            <div class="result-header">
              <h3>{{ selectedTask.topic }}</h3>
              <div class="result-meta">
                <el-tag :type="taskStatusType(selectedTask.status)" size="small">{{ taskStatusLabel(selectedTask.status) }}</el-tag>
                <span v-if="selectedTask.source_count">来源: {{ selectedTask.source_count }}篇</span>
                <span v-if="selectedTask.completed_at">完成: {{ selectedTask.completed_at }}</span>
                <el-button
                  v-if="selectedTask.status === 'completed'"
                  type="primary"
                  size="small"
                  plain
                  @click="downloadPdf"
                  :loading="pdfLoading"
                >下载 PDF</el-button>
              </div>
            </div>

            <div v-if="selectedTask.status === 'running' || selectedTask.status === 'queued'" class="running-tip">
              <el-progress :percentage="selectedTask.progress || 0" :stroke-width="16" text-inside />
              <p style="margin-top: 12px; color: #94A3B8;">正在执行深度研究，请稍候...</p>
            </div>

            <!-- 深度思考过程 -->
            <div v-if="selectedTask.thinking_process && selectedTask.status === 'completed'" class="thinking-process-section">
              <div class="thinking-process-header" @click="showThinking = !showThinking">
                <el-icon><component :is="showThinking ? ArrowDown : ArrowRight" /></el-icon>
                <span>深度思考过程</span>
              </div>
              <div v-show="showThinking" class="thinking-process-content">
                <div class="thinking-text" v-html="formatSynthesis(selectedTask.thinking_process)"></div>
              </div>
            </div>

            <div v-if="selectedTask.synthesis" class="synthesis" v-html="formatSynthesis(selectedTask.synthesis)" />

            <div v-else-if="selectedTask.error" class="error-msg">
              <el-alert :title="selectedTask.error" type="error" show-icon />
            </div>
          </div>
          <el-empty v-else description="选择左侧任务查看结果，或新建研究" :image-size="120" />
        </div>
      </el-col>
    </el-row>

    <!-- 新建研究对话框 -->
    <el-dialog v-model="showNewResearch" title="新建深度研究" width="500px">
      <el-form :model="newResearch" label-width="100px">
        <el-form-item label="研究主题">
          <el-input v-model="newResearch.topic" placeholder="输入研究主题，如：量子计算发展现状与趋势" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showNewResearch = false">取消</el-button>
        <el-button type="primary" @click="startResearch" :loading="starting">开始研究</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, reactive } from 'vue'
import { ArrowDown, ArrowRight, Delete } from '@element-plus/icons-vue'
import { getDeepResearches, getDeepResearch, createDeepResearch, deleteDeepResearch } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import html2pdf from 'html2pdf.js'
import { sendNotification } from '../../composables/useTauri'

// 配置 marked
marked.setOptions({
  breaks: true,
  gfm: true,
})

const tasks = ref<any[]>([])
const selectedTask = ref<any>(null)
const showNewResearch = ref(false)
const starting = ref(false)
const showThinking = ref(false)
const pdfLoading = ref(false)
const newResearch = reactive({ topic: '' })
// @ts-ignore
let pollTimer: ReturnType<typeof setInterval> | null = null

function taskStatusLabel(s: string) {
  return { queued: '排队中', running: '研究中', completed: '已完成', failed: '失败', cancelled: '已取消' }[s] || s
}

function taskStatusType(s: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  return { queued: 'info', running: 'warning', completed: 'success', failed: 'danger', cancelled: 'info' }[s] as any || 'info'
}

function formatSynthesis(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

async function loadTasks() {
  try {
    const res = await getDeepResearches({ page: 1, pageSize: 50 })
    tasks.value = res.data.items || []
  } catch (e) { console.error(e) }
}

async function selectTask(task: any) {
  try {
    const res = await getDeepResearch(task.id)
    selectedTask.value = res.data
  } catch (e) {
    selectedTask.value = task
  }
}

async function deleteTask(taskId: number) {
  try {
    await ElMessageBox.confirm('确定删除该研究任务及其所有结果？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteDeepResearch(taskId)
    ElMessage.success('研究任务已删除')
    loadTasks()
    if (selectedTask.value?.id === taskId) {
      selectedTask.value = null
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + (e.message || '未知错误'))
    }
  }
}

async function startResearch() {
  if (!newResearch.topic.trim()) {
    ElMessage.warning('请输入研究主题')
    return
  }
  starting.value = true
  try {
    const res = await createDeepResearch({ topic: newResearch.topic })
    ElMessage.success(res.data.message || '研究任务已创建')
    newResearch.topic = ''
    showNewResearch.value = false
    await loadTasks()
    // 选中新任务
    if (res.data.id) {
      await selectTask({ id: res.data.id })
    }
  } catch (e: any) {
    ElMessage.error('创建失败: ' + (e.message || '未知错误'))
  }
  starting.value = false
}

async function downloadPdf() {
  if (!selectedTask.value || !selectedTask.value.synthesis) return
  pdfLoading.value = true
  try {
    const element = document.querySelector('.research-result') as HTMLElement
    if (!element) {
      ElMessage.error('未找到要导出的研究内容')
      return
    }
    const opt = {
      margin: [12, 12, 12, 12] as [number, number, number, number],
      filename: `${selectedTask.value.topic || '深度研究报告'}.pdf`,
      image: { type: 'jpeg' as const, quality: 0.98 },
      html2canvas: { scale: 2, useCORS: true, backgroundColor: '#ffffff' },
      jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' as const }
    }
    await html2pdf().set(opt).from(element).save()
    ElMessage.success('PDF 下载成功')
  } catch (e: any) {
    ElMessage.error('PDF 下载失败: ' + (e.message || '未知错误'))
  } finally {
    pdfLoading.value = false
  }
}

// 轮询更新进行中的任务状态
function startPolling() {
  pollTimer = setInterval(async () => {
    const hasRunning = tasks.value.some(t => t.status === 'running' || t.status === 'queued')
    if (hasRunning) {
      const prevRunningIds = tasks.value
        .filter(t => t.status === 'running' || t.status === 'queued')
        .map(t => t.id)
      await loadTasks()
      // 检测刚完成的任务并发送通知
      for (const id of prevRunningIds) {
        const task = tasks.value.find(t => t.id === id)
        if (task && task.status === 'completed') {
          sendNotification('深度研究完成', `「${task.topic}」研究已完成，共 ${task.source_count || 0} 篇来源`)
        }
      }
      if (selectedTask.value && (selectedTask.value.status === 'running' || selectedTask.value.status === 'queued')) {
        await selectTask(selectedTask.value)
      }
    }
  }, 3000)
}

onMounted(() => {
  loadTasks()
  startPolling()
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.deep-research { background: white; border-radius: 8px; padding: 16px; }

.panel {
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  height: 550px;
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid #E2E8F0;
  font-weight: bold;
  font-size: 13px;
  color: #334155;
}

.task-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.task-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 6px;
  border: 1px solid #F1F5F9;
}

.task-item:hover { background: #F5F7FA; }
.task-item.active { background: #E8F0FE; border-color: #4A90D9; }

.task-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 6px;
}

.task-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.task-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.task-time { font-size: 11px; color: #94A3B8; }

.delete-btn {
  cursor: pointer;
  color: #94A3B8;
  font-size: 14px;
  transition: color 0.2s;
}

.delete-btn:hover {
  color: #E53E3E;
}

.result-panel {
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  min-height: 550px;
  padding: 20px;
}

.result-header { margin-bottom: 16px; }
.result-header h3 { margin: 0 0 8px; color: #1E293B; }
.result-meta { display: flex; gap: 12px; align-items: center; font-size: 12px; color: #64748B; }

.running-tip { text-align: center; padding: 40px 20px; }

.synthesis {
  font-size: 14px;
  line-height: 1.8;
  color: #334155;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.synthesis :deep(h1),
.synthesis :deep(h2),
.synthesis :deep(h3),
.synthesis :deep(h4) {
  font-weight: 600;
  line-height: 1.4;
  color: #1E293B;
}
.synthesis :deep(h1) { font-size: 22px; margin: 24px 0 12px; }
.synthesis :deep(h2) { font-size: 18px; margin: 20px 0 10px; }
.synthesis :deep(h3) { font-size: 15px; margin: 16px 0 8px; color: #334155; }
.synthesis :deep(h4) { font-size: 14px; margin: 12px 0 6px; color: #475569; }

.synthesis :deep(p) { margin: 8px 0; }
.synthesis :deep(ul),
.synthesis :deep(ol) { margin: 8px 0; padding-left: 24px; }
.synthesis :deep(li) { margin: 4px 0; }
.synthesis :deep(code) {
  background: rgba(0,0,0,0.06);
  padding: 2px 5px;
  border-radius: 3px;
  font-size: 0.9em;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.synthesis :deep(pre) {
  background: #1E293B;
  color: #E2E8F0;
  padding: 14px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
}
.synthesis :deep(pre code) { background: transparent; padding: 0; color: inherit; }
.synthesis :deep(blockquote) {
  border-left: 3px solid #4A90D9;
  padding-left: 14px;
  margin: 12px 0;
  color: #64748B;
}
.synthesis :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
  font-size: 13px;
}
.synthesis :deep(table th),
.synthesis :deep(table td) {
  border: 1px solid #E2E8F0;
  padding: 8px 12px;
  text-align: left;
}
.synthesis :deep(table th) { background: #F1F5F9; font-weight: 600; }
.synthesis :deep(table tr:nth-child(even)) { background: #F8FAFC; }
.synthesis :deep(strong) { font-weight: 600; }
.synthesis :deep(a) { color: #4A90D9; text-decoration: none; }
.synthesis :deep(a:hover) { text-decoration: underline; }
.synthesis :deep(hr) { border: none; border-top: 1px solid #E2E8F0; margin: 16px 0; }
.synthesis :deep(img) { max-width: 100%; border-radius: 6px; margin: 8px 0; }

.error-msg { padding: 20px; }

.thinking-process-section {
  margin-bottom: 20px;
  border: 1px dashed #94A3B8;
  border-radius: 8px;
  overflow: hidden;
}

.thinking-process-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #F8FAFC;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  color: #475569;
  user-select: none;
  transition: background 0.2s;
}

.thinking-process-header:hover {
  background: #F1F5F9;
}

.thinking-process-content {
  padding: 16px;
  background: #FAFBFC;
}

.thinking-text {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.7;
  color: #475569;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.thinking-text :deep(h1),
.thinking-text :deep(h2),
.thinking-text :deep(h3) {
  font-family: inherit;
  font-size: 14px;
  font-weight: 700;
  color: #334155;
  margin: 12px 0 6px;
}

.thinking-text :deep(strong) {
  color: #1E293B;
  font-weight: 700;
}

.thinking-text :deep(p) { margin: 6px 0; }
.thinking-text :deep(ul), .thinking-text :deep(ol) { margin: 6px 0; padding-left: 20px; }
.thinking-text :deep(li) { margin: 3px 0; }
</style>