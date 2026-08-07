<template>
  <el-dialog
    v-model="dialogVisible"
    :title="entry?.entryType === 'image' || entry?.entryType === 'table' ? '' : entry?.title"
    width="750px"
    destroy-on-close
    class="wiki-detail-dialog"
    append-to-body
    @closed="$emit('close')"
  >
    <div v-if="entry" class="entry-detail-container">
      <!-- YAML Frontmatter Panel -->
      <div class="wiki-frontmatter-panel">
        <!-- Identity Strip -->
        <div class="fm-identity">
          <div class="fm-icon-box" :style="{ backgroundColor: getTypeStyle(entry.entryType).bg }">
            <el-icon :size="18" :style="{ color: getTypeStyle(entry.entryType).color }">
              <component :is="getTypeStyle(entry.entryType).icon" />
            </el-icon>
          </div>
          <div class="fm-title-area">
            <h3 class="fm-title">{{ entry.title }}</h3>
            <div class="fm-meta-row">
              <span class="fm-badge-type" :style="{ color: getTypeStyle(entry.entryType).color, backgroundColor: getTypeStyle(entry.entryType).bg }">
                {{ getTypeStyle(entry.entryType).label }}
              </span>
              <span class="fm-meta-item" v-if="entry.createdAt">
                <el-icon style="margin-right: 3px;"><Calendar /></el-icon>
                {{ entry.createdAt }}
              </span>
              <template v-if="entry.keywords">
                <span v-for="tag in entry.keywords.split(',').filter(Boolean)" :key="tag" class="fm-meta-tag">
                  <el-icon style="margin-right: 3px;"><PriceTag /></el-icon>
                  {{ tag.trim() }}
                </span>
              </template>
            </div>
          </div>
        </div>

        <!-- Description -->
        <div class="fm-description" v-if="entry.description">
          {{ entry.description }}
        </div>

        <!-- Origin Callout -->
        <div class="fm-origin" v-if="entry.sourceOrigin">
          <span class="fm-origin-label">Origin:</span> {{ entry.sourceOrigin }}
        </div>

        <!-- Sources -->
        <div class="fm-section" v-if="entry.sourceName">
          <div class="fm-section-title">
            <el-icon style="margin-right: 4px;"><Collection /></el-icon>
            Sources
            <span class="fm-section-count">(1)</span>
          </div>
          <div class="fm-sources-list">
            <div class="fm-source-card">
              <span class="fm-source-icon">📄</span>
              <span class="fm-source-name">{{ entry.sourceName }}</span>
            </div>
          </div>
        </div>

        <!-- Related Links -->
        <div class="fm-section" v-if="entry.related">
          <div class="fm-section-title">
            <el-icon style="margin-right: 4px;"><Link /></el-icon>
            Related
            <span class="fm-section-count">({{ entry.related.split(',').filter(Boolean).length }})</span>
          </div>
          <div class="fm-related-chips">
            <span
              v-for="rel in entry.related.split(',').filter(Boolean)"
              :key="rel"
              class="fm-related-chip"
              @click="onRelatedClick(rel.trim())"
            >
              {{ rel.trim() }}
            </span>
          </div>
        </div>
      </div>

      <el-divider style="margin: 20px 0 16px 0;" />

      <!-- Main Body Content -->
      <div class="wiki-body-content">
        <!-- 1. 图片类型 -->
        <template v-if="entry.entryType === 'image'">
          <div class="dialog-image-box">
            <img :src="getMediaUrl(entry.mediaPath || '')" class="detail-image" alt="original-media" />
          </div>
          <div class="image-caption-text" v-if="entry.content">
            {{ entry.content }}
          </div>
        </template>

        <!-- 2. 表格类型 -->
        <template v-else-if="entry.entryType === 'table'">
          <div v-if="!editMode" class="detail-table-wrapper" style="border: none; padding: 0; background: none;">
            <div class="markdown-table-content markdown-body" v-html="renderMarkdownContent(entry.tableMarkdown || entry.content)"></div>
            <div style="margin-top: 20px; text-align: right;">
              <el-button type="primary" size="default" @click="startEditTable">
                编辑表格数据
              </el-button>
            </div>
          </div>
          <div v-else class="detail-edit-area">
            <el-input
              v-model="editTableMarkdown"
              type="textarea"
              :rows="14"
              placeholder="输入 Markdown 表格内容"
              style="font-family: monospace;"
            />
            <div style="margin-top: 16px;">
              <h4 style="margin-bottom: 8px; color: #475569; font-size: 13px;">实时预览</h4>
              <div class="edit-preview-box markdown-body" v-html="renderMarkdownContent(editTableMarkdown)"></div>
            </div>
            <div style="margin-top: 20px; text-align: right;">
              <el-button size="default" @click="cancelEditTable">取消</el-button>
              <el-button type="primary" size="default" @click="saveTableEdit" :loading="editSaving">
                保存并同步修改
              </el-button>
            </div>
          </div>
        </template>

        <!-- 3. 其他类型 -->
        <template v-else>
          <div class="entry-content markdown-body" v-html="renderMarkdownContent(entry.content)"></div>
        </template>
      </div>

    </div>
    <template #footer v-if="!editMode">
      <el-button @click="dialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { Calendar, PriceTag, Collection, Link } from '@element-plus/icons-vue'
import { updateTableMarkdown, getMediaUrl } from '../api'
import { ElMessage } from 'element-plus'
import { renderPreviewMarkdown } from '../utils/previewFormatting'

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

const props = defineProps<{
  entry: KnowledgeEntry | null
  visible: boolean
  wikiEntries: KnowledgeEntry[]
}>()

const emit = defineEmits<{
  close: []
  saved: []
  navigateToRelated: [target: KnowledgeEntry | string]
}>()

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => {
    if (!val) emit('close')
  },
})

const editMode = ref(false)
const editTableMarkdown = ref('')
const editSaving = ref(false)

function renderMarkdownContent(md: string): string {
  return renderPreviewMarkdown(md)
}

function getTypeStyle(type: string) {
  const t = (type || '').toLowerCase()
  switch (t) {
    case 'concept':
      return { label: '概念 (Concept)', icon: 'Notebook', color: '#2563eb', bg: '#eff6ff' }
    case 'entity':
      return { label: '实体 (Entity)', icon: 'OfficeBuilding', color: '#0d9488', bg: '#f0fdfa' }
    case 'thesis':
      return { label: '论点 (Thesis)', icon: 'Opportunity', color: '#7c3aed', bg: '#f5f3ff' }
    case 'methodology':
      return { label: '方法论 (Methodology)', icon: 'Setting', color: '#d97706', bg: '#fffbeb' }
    case 'finding':
      return { label: '发现 (Finding)', icon: 'Search', color: '#0891b2', bg: '#ecfeff' }
    case 'comparison':
      return { label: '对比 (Comparison)', icon: 'Switch', color: '#db2777', bg: '#fdf2f8' }
    case 'synthesis':
      return { label: '综合 (Synthesis)', icon: 'Files', color: '#4f46e5', bg: '#eef2ff' }
    case 'image':
      return { label: '图片 (Image)', icon: 'Picture', color: '#0891b2', bg: '#ecfeff' }
    case 'table':
      return { label: '表格 (Table)', icon: 'Grid', color: '#475569', bg: '#f1f5f9' }
    default:
      return { label: '词条 (Wiki)', icon: 'Document', color: '#4b5563', bg: '#f3f4f6' }
  }
}

function onRelatedClick(relTitle: string) {
  if (!relTitle) return
  const found = props.wikiEntries.find(
    e => e.title === relTitle || e.title.trim().toLowerCase() === relTitle.trim().toLowerCase()
  )
  if (found) {
    emit('navigateToRelated', found)
  } else {
    emit('navigateToRelated', relTitle as any)
  }
}

function startEditTable() {
  if (props.entry) {
    editTableMarkdown.value = props.entry.tableMarkdown || ''
    editMode.value = true
  }
}

function cancelEditTable() {
  editMode.value = false
}

async function saveTableEdit() {
  if (!props.entry) return
  editSaving.value = true
  try {
    await updateTableMarkdown(props.entry.id, editTableMarkdown.value)
    ElMessage.success('表格保存并同步成功')
    editMode.value = false
    emit('saved')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || '未知错误'))
  }
  editSaving.value = false
}
</script>

<style scoped>
.wiki-frontmatter-panel {
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 8px;
  box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.8), 0 1px 3px rgba(0, 0, 0, 0.02);
}
.fm-identity {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.fm-icon-box {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
.fm-title-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fm-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
}
.fm-meta-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
}
.fm-badge-type {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  text-transform: uppercase;
}
.fm-meta-item {
  color: #64748b;
  display: flex;
  align-items: center;
}
.fm-meta-tag {
  color: #64748b;
  display: flex;
  align-items: center;
  background: rgba(226, 232, 240, 0.6);
  padding: 1px 5px;
  border-radius: 4px;
}
.fm-description {
  font-size: 13px;
  line-height: 1.5;
  color: #475569;
  font-style: italic;
  margin-top: 8px;
  padding-left: 8px;
  border-left: 3px solid #cbd5e1;
}
.fm-origin {
  font-size: 12px;
  color: #1e293b;
  background: #eff6ff;
  border-left: 3px solid #3b82f6;
  padding: 6px 10px;
  border-radius: 0 6px 6px 0;
  margin-top: 10px;
}
.fm-origin-label {
  font-weight: 600;
  color: #2563eb;
}
.fm-section {
  margin-top: 12px;
  border-top: 1px dashed #e2e8f0;
  padding-top: 8px;
}
.fm-section-title {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  display: flex;
  align-items: center;
  margin-bottom: 6px;
}
.fm-section-count {
  color: #94a3b8;
  font-weight: normal;
  margin-left: 4px;
}
.fm-sources-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fm-source-card {
  display: flex;
  align-items: center;
  gap: 6px;
  background: white;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 12px;
  color: #334155;
  font-weight: 500;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}
.fm-source-icon {
  font-size: 14px;
}
.fm-source-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fm-related-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.fm-related-chip {
  font-size: 11px;
  font-weight: 500;
  color: #4f46e5;
  background: #f5f3ff;
  border: 1px solid #e0e7ff;
  padding: 3px 8px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
}
.fm-related-chip:hover {
  background: #e0e7ff;
  color: #3730a3;
  transform: translateY(-1px);
}
.preview-mention {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 700;
  border: 1px solid #bfdbfe;
}
.wiki-body-content {
  padding: 4px 8px;
}
.entry-content {
  font-size: 14px;
  line-height: 1.7;
  color: #334155;
  margin: 16px 0;
}
.dialog-image-box {
  display: flex;
  justify-content: center;
  background: #FAFBFC;
  border-radius: 8px;
  padding: 12px;
  border: 1px solid #E2E8F0;
}
.detail-image {
  max-width: 100%;
  max-height: 500px;
  object-fit: contain;
  display: block;
}
.detail-table-wrapper {
  margin-top: 8px;
}
.detail-edit-area {
  margin-top: 8px;
}
.edit-preview-box {
  margin-top: 8px;
  border: 1px solid #E2E8F0;
  border-radius: 6px;
  padding: 12px;
  max-height: 250px;
  overflow: auto;
  background: white;
}
.image-caption-text {
  font-size: 13px;
  color: #475569;
  line-height: 1.6;
  margin-top: 12px;
  text-align: center;
  background: #f8fafc;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #f1f5f9;
}
</style>

<style>
.wiki-detail-dialog .markdown-body table,
.wiki-detail-dialog .edit-preview-box table {
  border-collapse: collapse;
  width: 100%;
  margin: 8px 0;
  font-size: 12px;
}
.wiki-detail-dialog .markdown-body table th,
.wiki-detail-dialog .markdown-body table td,
.wiki-detail-dialog .edit-preview-box table th,
.wiki-detail-dialog .edit-preview-box table td {
  border: 1px solid #CBD5E1;
  padding: 6px 8px;
  text-align: left;
}
.wiki-detail-dialog .markdown-body table th,
.wiki-detail-dialog .edit-preview-box table th {
  background: #F1F5F9;
  font-weight: 600;
  color: #1E293B;
}
.wiki-detail-dialog .markdown-body table tr:nth-child(even),
.wiki-detail-dialog .edit-preview-box table tr:nth-child(even) {
  background: #F8FAFC;
}
.wiki-detail-dialog .markdown-body table tr:hover td,
.wiki-detail-dialog .edit-preview-box table tr:hover td {
  background: #EFF6FF;
}
</style>
