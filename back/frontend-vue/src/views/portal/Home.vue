<template>
  <div class="home-page">
    <HeroBanner />

    <WikiEntries
      :entries="entries"
      :loading="loadingEntries"
      :selected-entry-id="selectedEntry?.id ?? null"
      @entry-click="showDetail"
      @search="onWikiSearch"
    />

    <div class="section-divider"></div>

    <DocLibrary
      :doc-list="docList"
      :loading="loadingDocs"
      @doc-click="handleDocClick"
      @upload-click="showUpload = true"
      @delete-doc="handleDeleteDoc"
    />

    <!-- 上传新资料对话框 -->
    <el-dialog v-model="showUpload" title="上传新资料" width="600px" :close-on-click-modal="!uploading" append-to-body>
      <el-form :model="uploadForm" label-width="100px" v-if="!uploading">
        <el-form-item label="一级分类">
          <el-select v-model="uploadForm.categoryL1" placeholder="选择分类" clearable style="width: 100%;">
            <el-option label="政治" value="政治" />
            <el-option label="军事" value="军事" />
            <el-option label="经济" value="经济" />
            <el-option label="科技" value="科技" />
            <el-option label="社会" value="社会" />
          </el-select>
        </el-form-item>
        <el-form-item label="资料类型">
          <el-select v-model="uploadForm.docType" style="width: 100%;">
            <el-option label="研究报告" value="report" />
            <el-option label="动态信息" value="dynamic" />
            <el-option label="图书" value="translation" />
            <el-option label="图表" value="chart" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源信息">
          <el-input v-model="uploadForm.sourceOrigin" placeholder="可选：论文名、Blog URL、报告出处" clearable />
        </el-form-item>

        <template v-if="uploadForm.docType === 'chart'">
          <el-form-item label="图表分类">
            <el-radio-group v-model="uploadForm.chartType">
              <el-radio value="image">纯图片</el-radio>
              <el-radio value="table">数据表格 (自动OCR)</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="来源文档">
            <el-select v-model="uploadForm.sourceDocId" placeholder="选择已有文档（可选）" clearable filterable style="width: 100%">
              <el-option-group v-for="lib in libraries" :key="lib.key" :label="lib.name">
                <el-option
                  v-for="doc in docList[lib.key] || []"
                  :key="doc.id"
                  :label="doc.title"
                  :value="doc.id"
                />
              </el-option-group>
            </el-select>
            <div style="font-size: 11px; color: #94A3B8; margin-top: 2px;">
              如来源文档不在列表中，可先上传该文档再关联
            </div>
          </el-form-item>
          <el-form-item label="页码">
            <el-input-number v-model="uploadForm.sourcePage" :min="1" :max="9999" placeholder="来源页码" controls-position="right" />
            <span style="font-size: 11px; color: #94A3B8; margin-left: 8px;">可选：图表在原文件中的页码</span>
          </el-form-item>
        </template>

        <el-form-item label="上传文件">
          <div v-if="isTauriEnv" style="margin-bottom: 8px;">
            <el-button type="primary" plain size="small" @click="pickFilesHome">
              <el-icon><FolderOpened /></el-icon> 选择文件（系统对话框）
            </el-button>
            <span v-if="files.length > 0" style="margin-left: 8px; color: #67C23A; font-size: 13px;">已选择 {{ files.length }} 个文件</span>
          </div>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :on-change="handleFileChange"
            :limit="5"
            multiple
            drag
          >
            <el-icon style="font-size: 40px; color: #c0c4cc;"><UploadFilled /></el-icon>
            <div>拖拽或点击上传（Word/PDF/TXT/PPT/图片）</div>
          </el-upload>
        </el-form-item>
      </el-form>

      <div v-if="uploading" class="upload-progress-area">
        <div class="progress-header">
          <el-icon class="is-loading" style="font-size: 20px; color: #409EFF;"><Loading /></el-icon>
          <span>正在处理文件... ({{ uploadProgress.current }}/{{ uploadProgress.total }})</span>
        </div>
        <el-progress
          :percentage="uploadProgress.percentage"
          :stroke-width="10"
          style="margin: 16px 0"
        />
        <div class="progress-steps">
          <div :class="['step', { active: uploadProgress.step >= 1, done: uploadProgress.step > 1 }]">
            <span class="step-dot" /> 文件上传
          </div>
          <div :class="['step', { active: uploadProgress.step >= 2, done: uploadProgress.step > 2 }]">
            <span class="step-dot" /> 内容解析
          </div>
          <div :class="['step', { active: uploadProgress.step >= 3, done: uploadProgress.step > 3 }]">
            <span class="step-dot" /> LLM 知识抽取
          </div>
          <div :class="['step', { active: uploadProgress.step >= 4 }]">
            <span class="step-dot" /> 完成
          </div>
        </div>
        <div v-if="uploadProgress.currentFile" class="current-file">
          正在处理：{{ uploadProgress.currentFile }}
        </div>
        <div v-if="uploadProgress.log.length > 0" class="progress-log">
          <div v-for="(line, i) in uploadProgress.log" :key="i" class="log-line">{{ line }}</div>
        </div>
      </div>

      <template #footer>
        <el-button @click="showUpload = false" :disabled="uploading">取消</el-button>
        <el-button type="primary" @click="submitUpload" :loading="uploading" :disabled="uploading">
          {{ uploading ? '处理中...' : '上传并抽取' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 词条详情弹窗 -->
    <WikiDetailDialog
      :entry="selectedEntry"
      :visible="detailVisible"
      :wiki-entries="entries"
      @close="detailVisible = false"
      @saved="onEntrySaved"
      @navigate-to-related="onNavigateToRelated"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { UploadFilled, Loading, FolderOpened } from '@element-plus/icons-vue'
import { getKnowledgeEntries, getDocuments, uploadFile, getDocFileUrl, deleteDocument } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isTauri, openFileAsFiles, DOC_FILTERS, sendNotification } from '../../composables/useTauri'
import HeroBanner from '../../components/HeroBanner.vue'
import WikiEntries from '../../components/WikiEntries.vue'
import DocLibrary from '../../components/DocLibrary.vue'
import WikiDetailDialog from '../../components/WikiDetailDialog.vue'
import { getLibraryLabel } from '../../utils/libraryLabels'

const isTauriEnv = isTauri()

interface KnowledgeEntry {
  id: number
  title: string
  entryType: string
  library: string
  documentId?: number
  sourceName?: string
  content: string
  keywords: string
  status: string
  confidence?: number
  createdAt: string
  mediaType?: string
  mediaPath?: string
  sourceOrigin?: string
  tableMarkdown?: string
  projectId?: number
  description?: string
  related?: string
}

// --- 词条百科数据 ---
const entries = ref<KnowledgeEntry[]>([])
const loadingEntries = ref(false)
const detailVisible = ref(false)
const selectedEntry = ref<KnowledgeEntry | null>(null)

// --- 知识库文档 ---
const docList = ref<Record<string, any[]>>({})
const loadingDocs = ref(false)

// --- 上传 ---
const showUpload = ref(false)
const uploading = ref(false)
const files = ref<File[]>([])
const uploadForm = reactive({
  categoryL1: '',
  categoryL2: '',
  docType: 'report',
  sourceOrigin: '',
  sourceDocId: null as number | null,
  sourcePage: null as number | null,
  chartType: 'image',
})
const uploadProgress = reactive({
  current: 0,
  total: 0,
  percentage: 0,
  step: 0,
  currentFile: '',
  log: [] as string[],
})

const libraries = [
  { key: 'report', name: getLibraryLabel('report') },
  { key: 'dynamic', name: getLibraryLabel('dynamic') },
  { key: 'translation', name: getLibraryLabel('translation') },
  { key: 'chart', name: getLibraryLabel('chart') },
]

// --- API: 加载词条 ---
async function loadEntries(keyword?: string) {
  loadingEntries.value = true
  try {
    const params: any = { page: 1, pageSize: 250 }
    if (keyword) {
      params.keyword = keyword
    }
    const res = await getKnowledgeEntries(params)
    entries.value = res.data.items || []
  } catch (e) {
    console.error('加载词条列表失败', e)
  }
  loadingEntries.value = false
}

// --- API: 加载知识库文档 ---
async function loadDocStats() {
  loadingDocs.value = true
  for (const lib of libraries) {
    try {
      const res = await getDocuments({ docType: lib.key, page: 1, pageSize: 50 })
      docList.value[lib.key] = res.data.items || []
    } catch (e) {
      console.error(e)
    }
  }
  await loadChartTableMarkdown()
  loadingDocs.value = false
}

async function loadChartTableMarkdown() {
  const chartDocs = docList.value['chart']
  if (!chartDocs || chartDocs.length === 0) return
  try {
    const res = await getKnowledgeEntries({ library: 'chart', includeImage: true, page: 1, pageSize: 500 })
    const allEntries = res.data?.items || []
    for (const doc of chartDocs) {
      const entry = allEntries.find(
        (e: any) => e.documentId === doc.id && e.mediaType === 'table' && e.tableMarkdown
      )
      if (entry) {
        doc.tableMarkdown = entry.tableMarkdown
      }
    }
  } catch (e) {
    console.error('加载图表表格数据失败:', e)
  }
}

// --- 事件处理 ---
function showDetail(item: KnowledgeEntry) {
  selectedEntry.value = item
  detailVisible.value = true
}

function onWikiSearch(query: string) {
  loadEntries(query)
}

function onEntrySaved() {
  loadEntries()
}

async function onNavigateToRelated(target: KnowledgeEntry | string) {
  if (typeof target === 'object') {
    showDetail(target)
    return
  }
  const relatedTitle = target
  try {
    const res = await getKnowledgeEntries({ keyword: relatedTitle, page: 1, pageSize: 1 })
    const items = res.data?.items || []
    if (items.length > 0) {
      showDetail(items[0])
      return
    }
  } catch (e) {
    console.warn('API搜索关联词条失败:', e)
  }
  ElMessage.warning(`未找到词条: ${relatedTitle}`)
}

function handleDocClick(doc: any) {
  if (!doc) return
  if (doc.url || (doc.title && (doc.title.startsWith('http://') || doc.title.startsWith('https://')))) {
    const targetUrl = doc.url || doc.title
    window.open(targetUrl, '_blank')
  } else {
    window.open(getDocFileUrl(doc.id), '_blank')
  }
}

async function handleDeleteDoc(id: number) {
  try {
    await ElMessageBox.confirm('删除资料将同时移除其所有关联词条，确定继续？', '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    const res = await deleteDocument(id)
    const data = res.data
    ElMessage.success(`已删除，同步移除 ${data.deleted_entries ?? 0} 个关联词条`)
    await Promise.all([loadDocStats(), loadEntries()])
  } catch (e: any) {
    ElMessage.error('删除失败: ' + (e.message || '未知错误'))
  }
}

// --- 上传相关 ---
async function pickFilesHome() {
  const selected = await openFileAsFiles({ multiple: true, filters: DOC_FILTERS })
  files.value.push(...selected)
}

function handleFileChange(file: any) {
  files.value.push(file.raw)
}

async function submitUpload() {
  if (files.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  uploadProgress.total = files.value.length
  uploadProgress.current = 0
  uploadProgress.log = []

  for (const file of files.value) {
    uploadProgress.current++
    uploadProgress.currentFile = file.name
    uploadProgress.step = 1
    uploadProgress.percentage = Math.round(((uploadProgress.current - 1) / uploadProgress.total) * 100)
    uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 正在上传: ${file.name}`)

    const formData = new FormData()
    formData.append('file', file)
    formData.append('categoryL1', uploadForm.categoryL1)
    formData.append('categoryL2', uploadForm.categoryL2)
    formData.append('docType', uploadForm.docType)
    formData.append('sourceOrigin', uploadForm.sourceOrigin)
    if (uploadForm.sourceDocId) formData.append('sourceDocId', String(uploadForm.sourceDocId))
    if (uploadForm.sourcePage) formData.append('sourcePage', String(uploadForm.sourcePage))
    if (uploadForm.docType === 'chart') {
      formData.append('fileType', uploadForm.chartType)
    }

    try {
      uploadProgress.step = 2
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 解析内容: ${file.name}`)

      const res = await uploadFile(formData)
      uploadProgress.step = 3
      const entryCount = res.data.entry_count || 0
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] LLM抽取完成: ${entryCount} 个词条`)
    } catch (e: any) {
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 失败: ${e.message || '未知错误'}`)
      ElMessage.error(`上传失败 (${file.name}): ${e.message || '未知错误'}`)
    }
  }

  uploadProgress.step = 4
  uploadProgress.percentage = 100
  uploadProgress.currentFile = ''
  uploadProgress.log.push('全部处理完成')
  ElMessage.success(`上传完成：共${files.value.length}个文件`)
  sendNotification('知识抽取完成', `成功处理 ${files.value.length} 个文件，词条已入库`)

  uploading.value = false
  files.value = []
  showUpload.value = false

  await Promise.all([loadDocStats(), loadEntries()])
}

// --- 生命周期 ---
onMounted(async () => {
  await Promise.all([loadDocStats(), loadEntries()])
})
</script>

<style scoped>
.home-page { padding: 0px; }

.section-divider {
  height: 1px;
  background: linear-gradient(90deg, transparent, #CBD5E1, transparent);
  margin: 30px 0;
}

/* Upload progress */
.upload-progress-area {
  padding: 16px 0;
}
.progress-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #1F2937;
  margin-bottom: 4px;
}
.progress-steps {
  display: flex;
  justify-content: space-between;
  margin: 12px 0;
  padding: 0 8px;
}
.step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #CBD5E1;
  transition: color 0.3s;
}
.step.active { color: #409EFF; font-weight: 500; }
.step.done { color: #10B981; }
.step-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #E2E8F0;
  display: inline-block;
}
.step.active .step-dot { background: #409EFF; }
.step.done .step-dot { background: #10B981; }
.current-file {
  font-size: 12px;
  color: #64748B;
  margin-top: 8px;
  padding: 6px 10px;
  background: #F8FAFC;
  border-radius: 4px;
}
.progress-log {
  max-height: 120px;
  overflow-y: auto;
  margin-top: 8px;
  padding: 8px;
  background: #1E293B;
  border-radius: 6px;
}
.log-line {
  font-size: 11px;
  font-family: 'SF Mono', Menlo, monospace;
  color: #94A3B8;
  line-height: 1.8;
}
</style>
