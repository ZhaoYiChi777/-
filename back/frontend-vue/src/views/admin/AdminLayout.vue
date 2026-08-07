<template>
  <el-container class="admin-layout">
    <el-aside width="240px" class="admin-aside">
      <div class="aside-logo">智能情报管理系统</div>
      <el-menu router :default-active="$route.path" class="admin-menu" background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff">
        <el-menu-item index="/admin">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>

        <el-sub-menu index="info-lib">
          <template #title>
            <el-icon><Memo /></el-icon>
            <span>信息库</span>
          </template>
          <el-menu-item index="/admin/info-dynamic">动态信息</el-menu-item>
          <el-menu-item index="/admin/reports">研究报告</el-menu-item>
          <el-menu-item index="/admin/translations">图书</el-menu-item>
          <el-menu-item index="/admin/charts">图表</el-menu-item>
        </el-sub-menu>

        <el-menu-item index="/admin/projects">
          <el-icon><Folder /></el-icon>
          <span>项目库</span>
        </el-menu-item>

        <el-menu-item index="/admin/settings">
          <el-icon><Setting /></el-icon>
          <span>系统配置</span>
        </el-menu-item>

        <el-menu-item index="/admin/kg">
          <el-icon><Connection /></el-icon>
          <span>知识图谱</span>
        </el-menu-item>

        <el-menu-item index="/admin/sources">
          <el-icon><Document /></el-icon>
          <span>来源管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="header-left">
          <!-- 项目选择器 -->
          <el-dropdown trigger="click" @command="handleProjectCommand">
            <el-button size="small">
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
        </div>
        <div style="display: flex; align-items: center; gap: 16px;">
          <BackendStatus />
          <el-button @click="$router.push('/portal')" type="primary" size="small">返回前台</el-button>
        </div>
      </el-header>
      <el-main class="admin-main">
        <router-view :key="currentProjectId ?? 'default'" />
      </el-main>
    </el-container>

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
  </el-container>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import {
  Odometer, Memo, Folder, Setting, Connection, Document, Briefcase, ArrowDown
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getProjects, createProject, setCurrentProjectId, getCurrentProjectId } from '../../api'
import BackendStatus from '../../components/BackendStatus.vue'

interface Project {
  id: number
  name: string
  description: string
  status: string
}

const projects = ref<Project[]>([])
const currentProjectId = ref<number | null>(getCurrentProjectId())
const showCreateDialog = ref(false)
const creating = ref(false)
const newProject = reactive({ name: '', description: '' })

const currentProjectName = computed(() => {
  if (!currentProjectId.value) return '选择项目'
  const p = projects.value.find(p => p.id === currentProjectId.value)
  return p ? p.name : '未知项目'
})

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
    projects.value = res.data.items || []

    // 后台上传/操作依赖项目隔离，若未选择项目则默认选择第一个，避免数据归属为空
    const storedId = getCurrentProjectId()
    const exists = projects.value.some(p => p.id === storedId)
    if (storedId && exists) {
      currentProjectId.value = storedId
    } else if (projects.value.length > 0) {
      currentProjectId.value = projects.value[0].id
      setCurrentProjectId(projects.value[0].id)
    }
  } catch (e) {
    console.error('加载项目列表失败:', e)
  }
}

onMounted(loadProjects)
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}
.admin-aside {
  background-color: #304156;
  display: flex;
  flex-direction: column;
}
.aside-logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-weight: bold;
  background-color: #2b2f3a;
  font-size: 16px;
}
.admin-menu {
  border-right: none;
  flex: 1;
}
.admin-header {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e6e6e6;
  background-color: #fff;
  padding: 0 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.admin-main {
  background-color: #f0f2f5;
}
</style>
