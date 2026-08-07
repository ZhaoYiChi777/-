<template>
  <div class="libraries-section">
    <div class="libraries-header">
      <div class="libraries-title-wrap">
        <h2>知识库资料</h2>
        <span class="libraries-subtitle">上传并管理原文档</span>
      </div>
      <el-button type="primary" size="default" @click="$emit('uploadClick')">+ 上传新资料</el-button>
    </div>

    <div class="lib-list">
      <template v-if="loading">
        <div v-for="n in 4" :key="'skel-doc-' + n" class="doc-card" style="cursor: default; width: 220px; height: 150px;">
          <div class="doc-cover-wrapper" style="border-bottom: none; height: 100%;">
            <SkeletonLoader width="100%" height="100%" />
          </div>
        </div>
      </template>
      <template v-else>
        <div v-for="lib in libraries" :key="lib.key" class="lib-row">
          <div class="lib-row-header">
            <span class="lib-row-name">{{ lib.name }}</span>
            <el-badge :value="docList[lib.key]?.length || 0" class="lib-count-badge" />
          </div>
          <div class="lib-row-body">
            <template v-if="lib.key === 'chart'">
              <template v-if="chartImageItems.length > 0 || chartTableItems.length > 0">
                <!-- 图片行 -->
                <div v-if="chartImageItems.length > 0" class="chart-section">
                  <div class="chart-section-label">图片</div>
                  <div class="doc-preview horizontal-scroll">
                    <div
                      v-for="(doc, docIndex) in chartImageItems"
                      :key="doc.id"
                      class="doc-card chart-card"
                      :style="{ animationDelay: (docIndex % 20) * 0.04 + 's' }"
                      @click="$emit('docClick', doc)"
                    >
                      <div class="doc-cover-wrapper chart-cover" style="border-bottom: none;">
                        <img :src="getDocFileUrl(doc.id)" class="doc-cover-img" alt="Chart Preview" loading="lazy" />
                      </div>
                      <!-- 图表库直接删除功能 -->
                      <div class="chart-actions">
                        <el-button
                          v-if="pendingDeleteId === doc.id"
                          type="danger"
                          size="small"
                          @click.stop="confirmDelete(doc)"
                          class="chart-delete-confirm-btn"
                        >
                          确认删除
                        </el-button>
                        <el-button
                          v-else
                          size="small"
                          circle
                          type="danger"
                          @click.stop="armDelete(doc.id)"
                          class="chart-delete-btn"
                        >
                          <el-icon><Delete /></el-icon>
                        </el-button>
                      </div>
                    </div>
                  </div>
                </div>
                <!-- 表格行 -->
                <div v-if="chartTableItems.length > 0" class="chart-section">
                  <div class="chart-section-label">表格</div>
                  <div class="doc-preview horizontal-scroll">
                    <div
                      v-for="(doc, docIndex) in chartTableItems"
                      :key="doc.id"
                      class="doc-card chart-table-card"
                      :style="{ animationDelay: (docIndex % 20) * 0.04 + 's' }"
                      @click="$emit('docClick', doc)"
                    >
                      <div class="chart-table-header">
                        <span class="chart-table-title">{{ doc.title }}</span>
                        <!-- 数据表格直接删除功能 -->
                        <div class="chart-table-actions">
                          <el-button
                            v-if="pendingDeleteId === doc.id"
                            type="danger"
                            size="small"
                            plain
                            @click.stop="confirmDelete(doc)"
                            style="padding: 2px 6px; min-height: unset; font-size: 11px;"
                          >
                            确认
                          </el-button>
                          <el-button
                            v-else
                            size="small"
                            text
                            type="danger"
                            @click.stop="armDelete(doc.id)"
                            style="padding: 0; min-height: unset;"
                          >
                            <el-icon><Delete /></el-icon>
                          </el-button>
                        </div>
                      </div>
                      <div class="table-preview-mini" v-html="renderTablePreview(doc.tableMarkdown)"></div>
                    </div>
                  </div>
                </div>
              </template>
              <div v-else class="doc-empty">暂无上传图表</div>
            </template>
            <template v-else>
              <div v-if="docList[lib.key] && docList[lib.key].length > 0" class="doc-list-view">
                <div
                  v-for="(doc, docIndex) in (docList[lib.key] || [])"
                  :key="doc.id"
                  class="doc-list-item"
                  :style="{ animationDelay: (docIndex % 20) * 0.04 + 's' }"
                  @click="$emit('docClick', doc)"
                >
                  <div class="doc-type-badge" :class="getFileExt(doc.title).toLowerCase()">
                    {{ getFileExt(doc.title) }}
                  </div>
                  <div class="doc-item-main">
                    <span class="doc-title-link" :title="doc.title">{{ doc.title }}</span>
                    <div class="doc-item-meta">
                      <span class="doc-time">{{ doc.uploadTime }}</span>
                      <el-tag
                        size="small"
                        :type="getStatusType(doc.status)"
                        effect="light"
                        class="status-tag"
                      >
                        {{ getStatusLabel(doc.status) }}
                      </el-tag>
                    </div>
                  </div>
                  <div class="doc-item-actions">
                    <el-button
                      v-if="pendingDeleteId === doc.id"
                      type="danger"
                      size="small"
                      plain
                      @click.stop="confirmDelete(doc)"
                    >
                      确认
                    </el-button>
                    <el-button
                      v-else
                      size="small"
                      text
                      type="danger"
                      @click.stop="armDelete(doc.id)"
                      class="doc-delete-btn"
                    >
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </div>
                </div>
              </div>
              <div v-else class="doc-empty">
                <el-icon size="24" color="#CBD5E1"><Upload /></el-icon>
                <div>暂无上传文档</div>
                <div style="font-size: 11px; margin-top: 4px;">可拖拽文件到页面任意位置上传</div>
              </div>
            </template>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Delete, Upload } from '@element-plus/icons-vue'
import { getDocFileUrl } from '../api'
import { marked } from 'marked'
import SkeletonLoader from './SkeletonLoader.vue'
import { getLibraryLabel } from '../utils/libraryLabels'

const props = defineProps<{
  docList: Record<string, any[]>
  loading: boolean
}>()

const emit = defineEmits<{
  docClick: [doc: any]
  uploadClick: []
  deleteDoc: [id: number]
}>()

// Two-stage delete: first click arms, second click confirms
const pendingDeleteId = ref<number | null>(null)

// Auto-disarm after 3 seconds
watch(pendingDeleteId, (val) => {
  if (val !== null) {
    setTimeout(() => { pendingDeleteId.value = null }, 3000)
  }
})

function armDelete(id: number) {
  pendingDeleteId.value = id
}

function confirmDelete(doc: any) {
  pendingDeleteId.value = null
  emit('deleteDoc', doc.id)
}

// 计算属性：将图表库拆分为图片和表格
const chartImageItems = computed(() => {
  const items = props.docList['chart'] || []
  return items.filter(doc => !doc.tableMarkdown)
})

const chartTableItems = computed(() => {
  const items = props.docList['chart'] || []
  return items.filter(doc => !!doc.tableMarkdown)
})

marked.setOptions({
  breaks: true,
  gfm: true,
})

const libraries = [
  { key: 'report', name: getLibraryLabel('report') },
  { key: 'dynamic', name: getLibraryLabel('dynamic') },
  { key: 'translation', name: getLibraryLabel('translation') },
  { key: 'chart', name: getLibraryLabel('chart') },
]

function getFileExt(title: string): string {
  if (!title) return 'FILE'
  const lastDot = title.lastIndexOf('.')
  if (lastDot > 0) {
    const ext = title.substring(lastDot + 1).toUpperCase()
    return ext.length <= 4 ? ext : ext.substring(0, 4)
  }
  return 'FILE'
}

function getStatusType(status: string): string {
  switch (status) {
    case 'extracted': return 'success'
    case 'processing': return 'warning'
    case 'failed': return 'danger'
    default: return 'info'
  }
}

function getStatusLabel(status: string): string {
  switch (status) {
    case 'extracted': return '已解析'
    case 'processing': return '解析中'
    case 'failed': return '失败'
    default: return '未解析'
  }
}

function renderTablePreview(markdown: string): string {
  if (!markdown) return ''
  return marked.parse(markdown) as string
}
</script>

<style scoped>
.libraries-section {
  padding: 24px;
  background: white;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  margin-bottom: 24px;
}
.libraries-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.libraries-title-wrap h2 {
  font-size: 20px;
  color: var(--color-text-primary);
  font-weight: 700;
  margin: 0 0 4px;
}
.libraries-subtitle {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.lib-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.lib-row {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}
.lib-row-header {
  background: linear-gradient(180deg, var(--color-bg-secondary), var(--color-primary-light));
  padding: 12px 18px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.lib-count-badge {
  margin-left: 8px;
}
.lib-row-name {
  font-size: 14px;
  font-weight: bold;
  color: #1E3A8A;
}
.lib-row-body {
  padding: 16px 20px;
  background: white;
}
.doc-preview {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  width: 100%;
}
.doc-card {
  background: white;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  height: 240px;
  transition: all 0.2s ease-in-out;
  box-sizing: border-box;
  animation: docSlideUpFade 0.35s ease-out both;
}
.doc-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.doc-cover-wrapper {
  flex: 1;
  background: var(--color-bg-secondary);
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  border-bottom: 1px solid var(--color-border);
  height: 170px;
}
.doc-cover-img {
  width: 100%;
  height: 100%;
  max-height: 100%;
  object-fit: contain;
  object-position: center;
}
.doc-preview.horizontal-scroll {
  display: flex;
  overflow-x: auto;
  gap: 16px;
  padding-bottom: 12px;
  width: 100%;
}
.doc-preview.horizontal-scroll::-webkit-scrollbar {
  height: 6px;
}
.doc-preview.horizontal-scroll::-webkit-scrollbar-thumb {
  background: #CBD5E1;
  border-radius: 3px;
}
.doc-preview.horizontal-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chart-cover {
  height: 100%;
}
.doc-list-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.doc-list-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: white;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  transition: var(--transition-base);
  cursor: pointer;
  animation: docSlideUpFade 0.35s ease-out both;
}
.doc-list-item:hover {
  background: var(--color-primary-light);
  border-color: #BFDBFE;
  transform: translateX(2px);
}
.doc-type-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 8px;
  background: #E2E8F0;
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
  border: 1px solid #CBD5E1;
  text-transform: uppercase;
}
.doc-type-badge.pdf {
  background: #FEE2E2;
  color: #DC2626;
  border-color: #FCA5A5;
}
.doc-type-badge.docx, .doc-type-badge.doc {
  background: #DBEAFE;
  color: #2563EB;
  border-color: #93C5FD;
}
.doc-type-badge.xlsx, .doc-type-badge.xls, .doc-type-badge.csv {
  background: #D1FAE5;
  color: #059669;
  border-color: #6EE7B7;
}
.doc-type-badge.pptx, .doc-type-badge.ppt {
  background: #FFEDD5;
  color: #D97706;
  border-color: #FDBA74;
}
.doc-type-badge.md, .doc-type-badge.txt {
  background: #F3E8FF;
  color: #9333EA;
  border-color: #D8B4FE;
}
.doc-item-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.doc-title-link {
  font-size: 14px;
  font-weight: 600;
  color: #1E293B;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-item-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}
.doc-time {
  font-size: 12px;
  color: #64748B;
}
.status-tag {
  height: 18px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 16px;
}
.doc-item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.doc-delete-btn {
  opacity: 0;
  transition: opacity 0.2s;
}
.doc-list-item:hover .doc-delete-btn {
  opacity: 1;
}
.doc-empty {
  font-size: 13px;
  color: var(--color-text-tertiary);
  text-align: center;
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.table-preview-mini {
  flex: 1;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
  padding: 10px 12px;
  background: white;
}
.table-preview-mini :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
  border: 1px solid var(--color-border);
}
.table-preview-mini :deep(th),
.table-preview-mini :deep(td) {
  border: 1px solid var(--color-border);
  padding: 4px 6px;
  text-align: left;
}
.table-preview-mini :deep(th) {
  background: var(--color-primary-light);
  font-weight: 600;
  color: var(--color-primary);
}
.table-preview-mini :deep(tr:nth-child(even)) {
  background: var(--color-bg-secondary);
}

@keyframes docSlideUpFade {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.chart-section {
  margin-bottom: 16px;
}
.chart-section:last-child {
  margin-bottom: 0;
}
.chart-section-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
  padding-left: 2px;
}
.chart-card {
  position: relative;
  flex-shrink: 0;
  width: 220px;
  height: 150px;
  cursor: pointer;
}
.chart-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  opacity: 0;
  transition: opacity 0.2s ease-in-out;
  z-index: 10;
}
.chart-card:hover .chart-actions {
  opacity: 1;
}
.chart-delete-btn {
  background: rgba(239, 68, 68, 0.9) !important;
  border-color: rgba(239, 68, 68, 0.9) !important;
  color: white !important;
}
.chart-delete-btn:hover {
  background: #dc2626 !important;
  transform: scale(1.05);
}
.chart-delete-confirm-btn {
  font-size: 11px !important;
  padding: 4px 8px !important;
  background: #dc2626 !important;
  border-color: #dc2626 !important;
  color: white !important;
}
.chart-table-card {
  flex-shrink: 0;
  width: 320px;
  height: auto;
  min-height: 120px;
  max-height: 260px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
}
.chart-table-header {
  padding: 10px 12px;
  background: var(--color-bg-secondary);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.chart-table-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chart-table-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}
</style>
