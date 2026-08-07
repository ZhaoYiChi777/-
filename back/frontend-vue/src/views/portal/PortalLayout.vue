<template>
  <div class="portal-app">
    <!-- 顶部栏 -->
    <div class="top-bar">
      <span class="title">前台 — 分析工作空间</span>
      <div class="top-bar-right">
        <BackendStatus style="margin-right: 8px;" />
        <!-- 项目选择器 -->
        <el-dropdown trigger="click" @command="handleProjectCommand">
          <el-button 
            size="small" 
            class="project-select-trigger"
          >
            <el-icon style="margin-right: 4px;"><Briefcase /></el-icon>
            {{ currentProjectName }}
            <el-icon style="margin-left: 4px;"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="p in projects" :key="p.id" :command="p.id">
                {{ p.name }}
              </el-dropdown-item>
              <el-dropdown-item divided command="__new__">
                + 新建项目
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="primary" size="small" text class="admin-btn" @click="$router.push('/admin')">
          后台管理
        </el-button>
      </div>
    </div>

    <!-- 项目引导（未选择项目时显示） -->
    <div v-if="!currentProjectId" class="project-prompt">
      <div class="prompt-card">
        <h2>选择一个项目开始工作</h2>
        <p>每个项目的数据完全隔离，互不互通</p>
        <div class="prompt-actions">
          <el-select v-model="selectedProjectId" placeholder="选择已有项目" style="width: 240px;">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <el-button type="primary" @click="selectProject" :disabled="!selectedProjectId">进入项目</el-button>
          <el-button @click="showCreateDialog = true">新建项目</el-button>
        </div>
      </div>
    </div>

    <!-- Tab导航（选择项目后显示） -->
    <div v-if="currentProjectId" class="tab-nav">
      <div
        v-for="tab in tabs"
        :key="tab.key"
        :class="['tab-item', { active: activeTab === tab.key }]"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </div>
    </div>

    <!-- 内容区（keep-alive 保持切换时组件不销毁） -->
    <div v-if="currentProjectId" class="tab-content">
      <keep-alive>
        <component :is="activeTabComponent" />
      </keep-alive>
    </div>

    <!-- 新建项目对话框 -->
    <el-dialog v-model="showCreateDialog" title="新建项目" width="420px">
      <el-form :model="newProject" label-width="80px">
        <el-form-item label="项目名称">
          <el-input v-model="newProject.name" placeholder="输入项目名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="newProject.description" type="textarea" rows="3" placeholder="可选：项目描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateProject" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { Briefcase, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getProjects, createProject, setCurrentProjectId, getCurrentProjectId } from '../../api'
import BackendStatus from '../../components/BackendStatus.vue'
import Home from './Home.vue'
import SmartQA from './SmartQA.vue'
import DeepResearch from './DeepResearch.vue'

interface Project {
  id: number
  name: string
  description: string
  status: string
  createdAt: string
}

const tabs = [
  { key: 'home', label: '首页' },
  { key: 'qa', label: '智能问答' },
  { key: 'research', label: '深度研究' },
]

const activeTab = ref('home')
const projects = ref<Project[]>([])
const currentProjectId = ref<number | null>(getCurrentProjectId())
const selectedProjectId = ref<number | null>(null)
const showCreateDialog = ref(false)
const creating = ref(false)
const newProject = reactive({ name: '', description: '' })

const currentProjectName = computed(() => {
  if (!currentProjectId.value) return '选择项目'
  const p = projects.value.find(p => p.id === currentProjectId.value)
  return p ? p.name : '未知项目'
})

const activeTabComponent = computed(() => {
  switch (activeTab.value) {
    case 'home': return Home
    case 'qa': return SmartQA
    case 'research': return DeepResearch
    default: return Home
  }
})

function switchTab(key: string) {
  activeTab.value = key
}



function selectProject() {
  if (selectedProjectId.value) {
    currentProjectId.value = selectedProjectId.value
    setCurrentProjectId(selectedProjectId.value)
  }
}

function handleProjectCommand(command: string | number) {
  if (command === '__new__') {
    showCreateDialog.value = true
  } else {
    currentProjectId.value = Number(command)
    setCurrentProjectId(Number(command))
  }
}

async function handleCreateProject() {
  if (!newProject.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  creating.value = true
  try {
    const res = await createProject({ name: newProject.name, description: newProject.description })
    ElMessage.success('项目创建成功')
    showCreateDialog.value = false
    newProject.name = ''
    newProject.description = ''
    await loadProjects()
    // 自动选中新项目
    currentProjectId.value = res.data.id
    setCurrentProjectId(res.data.id)
  } catch (e: any) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  }
  creating.value = false
}

async function loadProjects() {
  try {
    const res = await getProjects()
    projects.value = res.data.items || res.data || []

    // 尊重用户已选项目：若 localStorage 中的项目仍有效则保留，不再自动重置为第一个项目
    const storedId = getCurrentProjectId()
    const exists = projects.value.some(p => p.id === storedId)
    if (storedId && exists) {
      currentProjectId.value = storedId
    } else if (projects.value.length > 0 && !currentProjectId.value) {
      // 仅在未选择任何项目时，默认进入第一个项目
      currentProjectId.value = projects.value[0].id
      setCurrentProjectId(projects.value[0].id)
    }
  } catch (e: any) {
    console.error('加载项目列表失败:', e)
  }
}

onMounted(loadProjects)
</script>

<style scoped>
.portal-app {
  min-height: 100vh;
  background: #FAFBFC;
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.top-bar {
  height: 48px;
  background: var(--gradient-header);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  border-radius: 0 0 10px 10px;
}

.top-bar .title {
  font-size: 18px;
  font-weight: bold;
  color: white;
}

.top-bar-right {
  position: absolute;
  right: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.project-prompt {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 100px);
}

.prompt-card {
  background: white;
  border-radius: 12px;
  padding: 40px 48px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  text-align: center;
}

.prompt-card h2 {
  margin: 0 0 8px;
  color: #1F2937;
}

.prompt-card p {
  margin: 0 0 24px;
  color: #64748B;
  font-size: 14px;
}

.prompt-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.tab-nav {
  display: flex;
  background: white;
  border-bottom: 2px solid #E2E8F0;
  padding: 0 20px;
  position: relative;
  z-index: 10;
}

.tab-item {
  padding: 14px 24px;
  cursor: pointer;
  font-size: 15px;
  color: #64748B;
  border-bottom: 3px solid transparent;
  transition: all var(--transition-base);
}

.tab-item:hover {
  opacity: 0.85;
  color: #2F5496;
}

.tab-item.active {
  color: #2F5496;
  border-bottom-color: #2F5496;
  font-weight: bold;
}

.tab-content {
  padding: 20px;
}

.project-select-trigger {
  color: #fff !important;
  background-color: rgba(255, 255, 255, 0.15) !important;
  border-color: rgba(255, 255, 255, 0.3) !important;
}
.project-select-trigger:hover,
.project-select-trigger:focus {
  background-color: rgba(255, 255, 255, 0.3);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.6);
}

.admin-btn {
  color: #fff;
}
.admin-btn:hover {
  color: #fff;
  background-color: rgba(255, 255, 255, 0.1);
}
</style>
