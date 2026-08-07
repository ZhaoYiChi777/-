<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ArrowRight, Check, Back } from '@element-plus/icons-vue'
import { createLlmConfig, createProject, setCurrentProjectId } from '../api'
import { ElMessage } from 'element-plus'
import { isTauri, openDirectory } from '../composables/useTauri'

const isVisible = ref(false)
const currentStep = ref(0)
const loading = ref(false)
const loadingText = ref('加载中...')

const steps = [
  { title: '欢迎使用', description: '智能情报分析平台介绍' },
  { title: '配置大模型', description: '连接 AI 推理大脑' },
  { title: '数据存储', description: '选择本地存储路径' },
  { title: '创建首个项目', description: '初始化知识空间' },
  { title: '大功告成', description: '准备开始工作' }
]

// Form State
const wizardForm = reactive({
  // Step 1: LLM
  llmProvider: 'deepseek',
  llmName: 'DeepSeek Chat',
  llmApiKey: '',
  llmModel: 'deepseek-chat',
  llmBaseUrl: 'https://api.deepseek.com',
  // Step 2: Storage
  customDataDir: '',
  // Step 3: Project
  projectName: '无人智能情报库',
  projectDesc: '我的第一个无人系统与智能装备情报分析项目'
})

const storageMode = ref('default')
const defaultDataDirPreview = ref('')

onMounted(async () => {
  // Web 开发模式：自动跳过向导（直接标记完成）
  if (!isTauri()) {
    localStorage.setItem('firstRunComplete', 'true')
    return
  }

  // 检测首次运行：优先使用 .first-run 文件（Tauri环境），降级到 localStorage（开发模式）
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      const isFirstRun = await invoke<boolean>('check_first_run')
      if (isFirstRun) {
        isVisible.value = true
      }
    } catch (e) {
      // Tauri API 调用失败，降级到 localStorage
      console.warn('[Wizard] check_first_run failed, falling back to localStorage:', e)
      const isComplete = localStorage.getItem('firstRunComplete')
      if (!isComplete) {
        isVisible.value = true
      }
    }
  } else {
    // 浏览器开发模式：使用 localStorage
    const isComplete = localStorage.getItem('firstRunComplete')
    if (!isComplete) {
      isVisible.value = true
    }
  }

  // Load default data dir preview
  if (isTauri()) {
    try {
      const { appDataDir } = await import('@tauri-apps/api/path')
      defaultDataDirPreview.value = await appDataDir()
    } catch (e) {
      console.error(e)
    }
  } else {
    defaultDataDirPreview.value = '浏览器环境：虚拟沙盒内存存储'
  }
})

async function handleSelectDataDir() {
  const dir = await openDirectory()
  if (dir) {
    wizardForm.customDataDir = dir
  }
}

const handleNext = async () => {
  if (currentStep.value === 1) {
    // Validate LLM step
    if (!wizardForm.llmApiKey.trim() && wizardForm.llmProvider !== 'ollama') {
      ElMessage.warning('请输入 API Key，或切换为 Ollama 本地部署')
      return
    }
  }

  if (currentStep.value === 2) {
    // Storage Step
    if (storageMode.value === 'custom' && !wizardForm.customDataDir.trim()) {
      ElMessage.warning('请选择自定义数据存储目录')
      return
    }

    if (storageMode.value === 'custom' && wizardForm.customDataDir) {
      loading.value = true
      loadingText.value = '正在应用自定义存储路径，重启本地引擎...'
      try {
        const { invoke } = await import('@tauri-apps/api/core')
        // 保存配置
        await invoke('save_desktop_config', {
          config: {
            custom_data_dir: wizardForm.customDataDir,
            close_action: 'minimize'
          }
        })
        // 停止旧后端
        await invoke('stop_backend')
        await new Promise(resolve => setTimeout(resolve, 1500))
        // 启动新后端
        await invoke('start_backend')
        // 轮询健康状态最多 30s
        let ready = false
        for (let i = 0; i < 30; i++) {
          try {
            const resp = await fetch('/api/health')
            if (resp.ok) {
              const json = await resp.json()
              // HealthController 返回 { status: "UP", ... }，不是 { code: 200 }
              if (json.status === 'UP') {
                ready = true
                break
              }
            }
          } catch {}
          await new Promise(resolve => setTimeout(resolve, 1000))
        }
        if (!ready) {
          throw new Error('重启引擎超时，请检查引擎日志或手动重启。')
        }
      } catch (e: any) {
        ElMessage.error('应用路径并重连引擎失败: ' + e.message)
        loading.value = false
        return
      }
      loading.value = false
    }
  }

  if (currentStep.value === 3) {
    // Validate project name
    if (!wizardForm.projectName.trim()) {
      ElMessage.warning('请输入项目名称')
      return
    }
    
    // Save LLM & Project
    loading.value = true
    loadingText.value = '正在为您配置环境并创建首个项目...'
    try {
      // 1. Create LLM Config
      await createLlmConfig({
        name: wizardForm.llmName,
        provider: wizardForm.llmProvider,
        apiKey: wizardForm.llmApiKey,
        model: wizardForm.llmModel,
        baseUrl: wizardForm.llmBaseUrl,
        purpose: 'both', // Use for both Chat and Extract
        enabled: true
      })

      // 2. Create Initial Project
      const projRes = await createProject({
        name: wizardForm.projectName,
        description: wizardForm.projectDesc
      })

      if (projRes.data && projRes.data.id) {
        setCurrentProjectId(projRes.data.id)
      }
      
      currentStep.value++
    } catch (e: any) {
      console.error('[Wizard] Failed to save initial config:', e)
      ElMessage.error('初始化配置失败: ' + (e.message || '网络连接错误'))
    } finally {
      loading.value = false
    }
  } else {
    currentStep.value++
  }
}

const handlePrev = () => {
  if (currentStep.value > 0) {
    currentStep.value--
  }
}

const handleFinish = async () => {
  // Tauri 环境：创建 .first-run 标志文件
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('mark_first_run_complete')
    } catch (e) {
      console.error('[Wizard] Failed to mark first run complete:', e)
    }
  }
  // 开发模式降级：仍使用 localStorage
  localStorage.setItem('firstRunComplete', 'true')
  isVisible.value = false
  // Reload page to apply state correctly
  window.location.reload()
}

const onProviderChange = (val: string) => {
  if (val === 'deepseek') {
    wizardForm.llmName = 'DeepSeek'
    wizardForm.llmBaseUrl = 'https://api.deepseek.com'
    wizardForm.llmModel = 'deepseek-chat'
  } else if (val === 'siliconflow') {
    wizardForm.llmName = 'SiliconFlow Qwen2.5'
    wizardForm.llmBaseUrl = 'https://api.siliconflow.cn/v1'
    wizardForm.llmModel = 'Qwen/Qwen2.5-72B-Instruct'
  } else if (val === 'openai') {
    wizardForm.llmName = 'OpenAI GPT-4o'
    wizardForm.llmBaseUrl = ''
    wizardForm.llmModel = 'gpt-4o'
  } else if (val === 'google') {
    wizardForm.llmName = 'Google Gemini'
    wizardForm.llmBaseUrl = ''
    wizardForm.llmModel = 'gemini-2.5-flash'
  } else if (val === 'ollama') {
    wizardForm.llmName = 'Ollama (Local)'
    wizardForm.llmBaseUrl = 'http://localhost:11434'
    wizardForm.llmModel = 'qwen2.5'
  }
}
</script>

<template>
  <el-dialog
    v-model="isVisible"
    title="新用户引导向导"
    width="680px"
    class="wizard-dialog"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    append-to-body
  >
    <div class="wizard-container">
      <!-- Steps Navigation -->
      <el-steps :active="currentStep" finish-status="success" align-center class="steps-nav">
        <el-step v-for="step in steps" :key="step.title" :title="step.title" :description="step.description" />
      </el-steps>

      <div class="step-content" v-loading="loading" :element-loading-text="loadingText">
        <!-- Step 0: Welcome -->
        <div v-if="currentStep === 0" class="welcome-step text-center">
          <div class="icon-avatar">
            <svg viewBox="0 0 100 100" class="brain-svg">
              <circle cx="50" cy="50" r="45" fill="none" stroke="#00f2fe" stroke-width="2" />
              <path d="M30 50 C30 30, 70 30, 70 50 C70 70, 30 70, 30 50 Z" fill="none" stroke="#0072ff" stroke-width="2" />
              <circle cx="50" cy="50" r="10" fill="#00f2fe" />
            </svg>
          </div>
          <h2>欢迎使用 智能情报 analysis 平台</h2>
          <p class="welcome-desc">
            这是一款面向无人系统（无人机/无人车/无人船/无人潜航器）研究分析人员设计的专业化个人知识管理与分析应用。
            基于 Graph-RAG (图增强检索) 技术，将零散文档自动抽取为知识词条，并深度连通。
          </p>
          <div class="welcome-features">
            <div class="feat-item">📂 <strong>多格式导入</strong>: 拖拽即可解析 PDF、Word、Excel 及图片 OCR</div>
            <div class="feat-item">🕸️ <strong>图谱分析</strong>: Louvain 社区检测算法与四信号关联挖掘</div>
            <div class="feat-item">💬 <strong>Graph-RAG 问答</strong>: 可对答案做多文档交叉验证和引用溯源</div>
          </div>
        </div>

        <!-- Step 1: LLM Setup -->
        <div v-else-if="currentStep === 1" class="form-step">
          <h3>配置大语言模型 (AI 脑)</h3>
          <p class="step-desc">平台中所有的文本分类、知识抽取、智能问答与深度研究均依靠大模型驱动。</p>
          
          <el-form label-width="120px" class="wizard-form">
            <el-form-item label="服务商 Preset">
              <el-select v-model="wizardForm.llmProvider" placeholder="选择服务商" @change="onProviderChange" style="width: 100%;">
                <el-option label="DeepSeek (推荐)" value="deepseek" />
                <el-option label="SiliconFlow (硅基流动)" value="siliconflow" />
                <el-option label="OpenAI (GPT)" value="openai" />
                <el-option label="Google Gemini" value="google" />
                <el-option label="Ollama (本地运行)" value="ollama" />
              </el-select>
            </el-form-item>

            <el-form-item label="API Key" v-if="wizardForm.llmProvider !== 'ollama'">
              <el-input v-model="wizardForm.llmApiKey" type="password" show-password placeholder="请输入 sk-... 密钥" />
            </el-form-item>

            <el-form-item label="模型名称">
              <el-input v-model="wizardForm.llmModel" placeholder="输入模型ID，如 deepseek-chat" />
            </el-form-item>

            <el-form-item label="Base URL" v-if="['siliconflow', 'deepseek', 'ollama'].includes(wizardForm.llmProvider)">
              <el-input v-model="wizardForm.llmBaseUrl" placeholder="API 请求入口 URL" />
            </el-form-item>
          </el-form>
        </div>

        <!-- Step 2: Storage Path Selection -->
        <div v-else-if="currentStep === 2" class="form-step">
          <h3>选择数据存储目录</h3>
          <p class="step-desc">平台的所有数据（包括 SQLite 数据库、导出的实体图谱及上传的文档物理文件）将存放于该目录中。</p>
          
          <el-form label-width="120px" class="wizard-form">
            <el-form-item label="存储模式">
              <el-radio-group v-model="storageMode">
                <el-radio label="default">默认系统路径 (推荐)</el-radio>
                <el-radio label="custom">自定义本地路径</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="存储路径" v-if="storageMode === 'custom'">
              <div style="display: flex; gap: 10px; width: 100%;">
                <el-input v-model="wizardForm.customDataDir" placeholder="点击右侧按钮选择目录..." readonly />
                <el-button type="primary" @click="handleSelectDataDir">选择目录</el-button>
              </div>
              <div class="form-hint" style="color: #94A3B8; font-size: 12px; margin-top: 4px;">
                选择空文件夹或者已有数据库的文件夹。
              </div>
            </el-form-item>
            <el-form-item label="路径预览" v-else>
              <el-input v-model="defaultDataDirPreview" readonly disabled />
            </el-form-item>
          </el-form>
        </div>

        <!-- Step 3: Create Project -->
        <div v-else-if="currentStep === 3" class="form-step">
          <h3>创建首个知识库项目</h3>
          <p class="step-desc">平台使用项目制进行隔离，一个项目包含独立的 SQLite 数据库文件、文档和独立的知识图谱。</p>
          
          <el-form label-width="120px" class="wizard-form">
            <el-form-item label="项目名称">
              <el-input v-model="wizardForm.projectName" placeholder="输入项目名称" />
            </el-form-item>
            <el-form-item label="项目描述">
              <el-input v-model="wizardForm.projectDesc" type="textarea" rows="4" placeholder="可选：简要记录项目的研究范围和目标" />
            </el-form-item>
          </el-form>
        </div>

        <!-- Step 4: Success -->
        <div v-else-if="currentStep === 4" class="success-step text-center">
          <div class="success-icon-wrap">
            <el-icon class="success-icon" :size="60"><Check /></el-icon>
          </div>
          <h2>配置全部就绪！</h2>
          <p class="success-desc">
            您的第一个项目 <strong>「{{ wizardForm.projectName }}」</strong> 已经成功创建。
            现在您可以上传文件到资料库、构建图谱或者直接向 AI 提问了。
          </p>
          
          <div class="next-steps-list">
            <div class="next-step-card">
              <div class="n-step-icon">1</div>
              <div>
                <strong>导入资料文档</strong>
                <p>点击前台的“研究报告”选项卡，进入上传页面拖入您的文档。</p>
              </div>
            </div>
            <div class="next-step-card">
              <div class="n-step-icon">2</div>
              <div>
                <strong>构建图谱与问答</strong>
                <p>摄入文件完毕后在后台启动“重建图谱”，即可开始多维度 Graph-RAG 对话！</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="wizard-footer">
        <el-button v-if="currentStep > 0 && currentStep < 4" @click="handlePrev">
          <el-icon><Back /></el-icon> 上一步
        </el-button>
        <el-button v-if="currentStep < 4" type="primary" @click="handleNext" :loading="loading">
          下一步 <el-icon><ArrowRight /></el-icon>
        </el-button>
        <el-button v-else type="success" @click="handleFinish">
          立即开启探索 <el-icon><Check /></el-icon>
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.wizard-dialog) {
  background: #151720 !important;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}
:deep(.wizard-dialog .el-dialog__title) {
  color: #ffffff;
  font-weight: 600;
  text-align: center;
  display: block;
}

.wizard-container {
  color: #b0b5c1;
  font-family: 'Outfit', 'Inter', sans-serif;
  padding: 10px 0;
}

.steps-nav {
  margin-bottom: 30px;
}

:deep(.el-step__title) {
  font-size: 13px !important;
  font-weight: 600;
}
:deep(.el-step__description) {
  font-size: 11px !important;
}

.step-content {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.text-center {
  text-align: center;
}

/* Welcome Step */
.icon-avatar {
  width: 70px;
  height: 70px;
  margin: 0 auto 16px;
  filter: drop-shadow(0 0 10px rgba(0, 242, 254, 0.3));
}
.brain-svg {
  width: 100%;
  height: 100%;
}

.welcome-step h2 {
  color: #ffffff;
  margin: 0 0 10px 0;
  font-size: 20px;
}

.welcome-desc {
  font-size: 13.5px;
  line-height: 1.6;
  color: #8a8f98;
  max-width: 580px;
  margin: 0 auto 20px;
}

.welcome-features {
  display: inline-block;
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  padding: 16px 20px;
  border-radius: 8px;
  max-width: 500px;
}

.feat-item {
  font-size: 12.5px;
  margin-bottom: 8px;
  color: #b0b5c1;
}
.feat-item:last-child {
  margin-bottom: 0;
}

.feat-item strong {
  color: #00f2fe;
}

/* Form Steps */
.form-step h3 {
  color: #ffffff;
  margin: 0 0 6px 0;
  font-size: 18px;
}
.step-desc {
  font-size: 13px;
  color: #8a8f98;
  margin: 0 0 20px 0;
}

.wizard-form {
  margin-top: 10px;
}

:deep(.el-form-item__label) {
  color: #a0a6b5 !important;
  font-weight: 500;
}

/* Success Step */
.success-icon-wrap {
  width: 70px;
  height: 70px;
  background: rgba(103, 194, 58, 0.1);
  border: 2px solid #67c23a;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
  color: #67c23a;
  box-shadow: 0 0 12px rgba(103, 194, 58, 0.2);
}

.success-step h2 {
  color: #ffffff;
  font-size: 22px;
  margin: 0 0 10px 0;
}

.success-desc {
  font-size: 14px;
  line-height: 1.6;
  max-width: 520px;
  margin: 0 auto 24px;
}

.next-steps-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 500px;
  margin: 0 auto;
  text-align: left;
}

.next-step-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  padding: 12px 16px;
}

.n-step-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #0072ff;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 13px;
  flex-shrink: 0;
}

.next-step-card strong {
  color: #ffffff;
  font-size: 13px;
}

.next-step-card p {
  margin: 4px 0 0 0;
  font-size: 12px;
  color: #8a8f98;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
