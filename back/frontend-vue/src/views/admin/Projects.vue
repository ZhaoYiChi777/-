<template>
  <div class="projects-page">
    <!-- 项目列表视图 -->
    <el-card v-if="!selectedProject">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>项目库</span>
          <div>
            <el-input v-model="keyword" placeholder="搜索项目" style="width: 200px; margin-right: 10px;" clearable @keyup.enter="loadData" />
            <el-button type="primary" size="small" @click="showAdd = true">新建项目</el-button>
          </div>
        </div>
      </template>
      <el-table :data="items" stripe v-loading="loading" @row-click="showProjectDetail">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="项目名称" min-width="150" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="docCount" label="文档数" width="80" align="center" />
        <el-table-column prop="entryCount" label="词条数" width="80" align="center" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '活跃' : row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click.stop="showProjectDetail(row)">详情</el-button>
            <el-button type="danger" size="small" link @click.stop="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无项目，请点击【新建项目】创建" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 项目详情视图 -->
    <el-card v-if="selectedProject">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <div>
            <el-button @click="selectedProject = null" size="small" style="margin-right: 12px;">
              <el-icon><ArrowLeft /></el-icon> 返回
            </el-button>
            <span style="font-size: 16px; font-weight: bold;">{{ selectedProject.name }}</span>
            <el-tag :type="selectedProject.status === 'active' ? 'success' : 'info'" size="small" style="margin-left: 8px;">
              {{ selectedProject.status === 'active' ? '活跃' : selectedProject.status }}
            </el-tag>
          </div>
        </div>
      </template>

      <!-- 项目概览 -->
      <div class="project-stats">
        <el-statistic title="文档数" :value="selectedProject.docCount || 0" />
        <el-statistic title="知识词条" :value="selectedProject.entryCount || 0" />
        <el-statistic title="问答记录" :value="selectedProject.qaCount || 0" />
        <el-statistic title="研究报告" :value="selectedProject.reportCount || 0" />
      </div>

      <!-- 项目描述 -->
      <div v-if="selectedProject.description" class="project-description">
        <h4>项目描述</h4>
        <p>{{ selectedProject.description }}</p>
      </div>

      <!-- Tab: 文档/词条/问答 -->
      <el-tabs v-model="detailTab" style="margin-top: 16px;">
        <el-tab-pane label="文档" name="docs">
          <el-table :data="projectDocs" stripe v-loading="docsLoading" empty-text="暂无文档">
            <el-table-column prop="title" label="文件名" min-width="200" />
            <el-table-column prop="docType" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ row.docType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'parsed' || row.status === 'extracted' ? 'success' : 'info'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="uploadTime" label="上传时间" width="160" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="知识词条" name="entries">
          <el-table :data="projectEntries" stripe v-loading="entriesLoading" empty-text="暂无词条">
            <el-table-column prop="title" label="标题" min-width="180" />
            <el-table-column prop="entryType" label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small">{{ row.entryType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="内容" min-width="250" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'approved' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="问答记录" name="qa">
          <el-table :data="projectQa" stripe v-loading="qaLoading" empty-text="暂无问答">
            <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="answer" label="回答" min-width="300" show-overflow-tooltip />
            <el-table-column prop="confidence" label="置信度" width="80">
              <template #default="{ row }">{{ (row.confidence * 100).toFixed(0) }}%</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="160" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新建项目对话框 -->
    <el-dialog v-model="showAdd" title="新建项目" width="500px">
      <el-form :model="newProject" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="newProject.name" placeholder="输入项目名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="newProject.description" type="textarea" rows="3" placeholder="可选：项目描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAdd = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getProjects, createProject, deleteProject, getProjectDetail } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const items = ref<any[]>([])
const keyword = ref('')
const loading = ref(false)
const showAdd = ref(false)
const newProject = reactive({ name: '', description: '' })

const selectedProject = ref<any>(null)
const detailTab = ref('docs')
const projectDocs = ref<any[]>([])
const projectEntries = ref<any[]>([])
const projectQa = ref<any[]>([])
const docsLoading = ref(false)
const entriesLoading = ref(false)
const qaLoading = ref(false)

async function loadData() {
  loading.value = true
  try {
    const res = await getProjects()
    items.value = res.data.items || []
  } catch (e) { console.error(e) }
  loading.value = false
}

async function showProjectDetail(row: any) {
  selectedProject.value = row
  detailTab.value = 'docs'
  await loadProjectData()
}

async function loadProjectData() {
  if (!selectedProject.value) return
  const projectId = selectedProject.value.id

  docsLoading.value = true
  entriesLoading.value = true
  qaLoading.value = true

  try {
    const res = await getProjectDetail(projectId)
    const data = res.data
    projectDocs.value = data.documents || []
    projectEntries.value = data.entries || []
    projectQa.value = data.qaRecords || []
  } catch (e) {
    console.error(e)
  }

  docsLoading.value = false
  entriesLoading.value = false
  qaLoading.value = false
}

async function handleCreate() {
  if (!newProject.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  try {
    await createProject(newProject)
    ElMessage.success('创建成功')
    showAdd.value = false
    newProject.name = ''
    newProject.description = ''
    loadData()
  } catch (e: any) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该项目？项目下的所有文档和词条也将被删除。', '提示', { type: 'warning' })
  try {
    await deleteProject(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e: any) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

onMounted(loadData)
</script>

<style scoped>
.projects-page { padding: 0; }
.project-stats {
  display: flex;
  gap: 32px;
  padding: 16px 0;
  border-bottom: 1px solid #E2E8F0;
}
.project-description {
  margin-top: 16px;
}
.project-description h4 {
  margin: 0 0 8px;
  color: #334155;
}
.project-description p {
  color: #64748B;
  line-height: 1.6;
}
</style>
