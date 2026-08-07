/**
 * useTauri - Tauri API 封装 composable
 * 所有函数都有 Web 降级方案（非 Tauri 环境使用浏览器 API）
 */

/** 检测是否在 Tauri 环境 */
export function isTauri(): boolean {
  return '__TAURI_INTERNALS__' in window
}

/** 文件过滤器类型 */
export interface FileFilter {
  name: string
  extensions: string[]
}

/**
 * 打开文件选择对话框（支持多文件、文件类型过滤）
 * Tauri 环境：使用 @tauri-apps/plugin-dialog
 * Web 降级：使用 <input type="file"> 弹出选择
 */
export async function openFile(options?: {
  multiple?: boolean
  filters?: FileFilter[]
}): Promise<string[]> {
  const { multiple = false, filters } = options || {}

  if (isTauri()) {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const result = await open({
      multiple,
      filters: filters?.map(f => ({ name: f.name, extensions: f.extensions })),
    })
    if (!result) return []
    return Array.isArray(result) ? result : [result]
  }

  // Web 降级：使用 <input type="file">
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = multiple
    if (filters) {
      input.accept = filters.map(f => f.extensions.map(ext => `.${ext}`).join(',')).join(',')
    }
    input.onchange = () => {
      const fileList = input.files
      if (!fileList || fileList.length === 0) {
        resolve([])
        return
      }
      // Web 环境返回文件名（无法获取完整路径）
      resolve(Array.from(fileList).map(f => f.name))
    }
    input.oncancel = () => resolve([])
    input.click()
  })
}

/**
 * 打开文件选择对话框并返回 File 对象数组
 * Tauri 环境：使用 dialog 选择文件 + fs 读取内容 → 构造 File 对象
 * Web 降级：使用 <input type="file"> 直接获取 File 对象
 * 用于与 el-upload 的 files 数组集成
 */
export async function openFileAsFiles(options?: {
  multiple?: boolean
  filters?: FileFilter[]
}): Promise<File[]> {
  const { multiple = false, filters } = options || {}

  if (isTauri()) {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const result = await open({
      multiple,
      filters: filters?.map(f => ({ name: f.name, extensions: f.extensions })),
    })
    if (!result) return []

    const paths = Array.isArray(result) ? result : [result]
    const { readFile } = await import('@tauri-apps/plugin-fs')
    const files: File[] = []

    for (const filePath of paths) {
      try {
        const content = await readFile(filePath)
        const fileName = filePath.split(/[\\/]/).pop() || 'unknown'
        const ext = fileName.split('.').pop()?.toLowerCase() || ''
        const mimeMap: Record<string, string> = {
          pdf: 'application/pdf',
          doc: 'application/msword',
          docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          xls: 'application/vnd.ms-excel',
          xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          ppt: 'application/vnd.ms-powerpoint',
          pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
          txt: 'text/plain',
          md: 'text/markdown',
          csv: 'text/csv',
          jpg: 'image/jpeg',
          jpeg: 'image/jpeg',
          png: 'image/png',
          gif: 'image/gif',
          svg: 'image/svg+xml',
          webp: 'image/webp',
        }
        const mime = mimeMap[ext] || 'application/octet-stream'
        const file = new File([content], fileName, { type: mime })
        files.push(file)
      } catch (e) {
        console.error(`[useTauri] Failed to read file: ${filePath}`, e)
      }
    }
    return files
  }

  // Web 降级：使用 <input type="file">
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = multiple
    if (filters) {
      input.accept = filters.map(f => f.extensions.map(ext => `.${ext}`).join(',')).join(',')
    }
    input.onchange = () => {
      const fileList = input.files
      if (!fileList || fileList.length === 0) {
        resolve([])
        return
      }
      resolve(Array.from(fileList))
    }
    input.oncancel = () => resolve([])
    input.click()
  })
}

/** 常用文档类型过滤器 */
export const DOC_FILTERS: FileFilter[] = [
  {
    name: '文档',
    extensions: ['pdf', 'doc', 'docx', 'wps', 'xls', 'xlsx', 'et', 'ppt', 'pptx', 'dps', 'odt', 'ods', 'odp', 'txt', 'md', 'markdown', 'rtf', 'csv'],
  },
  {
    name: '图片',
    extensions: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'tiff', 'tif', 'svg', 'ico', 'heic', 'heif', 'avif'],
  },
]

/**
 * 打开目录选择对话框
 * Tauri 环境：使用 @tauri-apps/plugin-dialog
 * Web 降级：使用 <input type="file" webkitdirectory>
 */
export async function openDirectory(): Promise<string | null> {
  if (isTauri()) {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const result = await open({ directory: true, multiple: false })
    return result || null
  }

  // Web 降级：使用 webkitdirectory
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    // @ts-ignore - webkitdirectory 是 WebKit 专有属性
    input.webkitdirectory = true
    input.onchange = () => {
      const fileList = input.files
      if (!fileList || fileList.length === 0) {
        resolve(null)
        return
      }
      // 返回第一个文件的 webkitRelativePath 的根目录名
      const relativePath = fileList[0].webkitRelativePath
      const rootDir = relativePath.split('/')[0]
      resolve(rootDir || null)
    }
    input.oncancel = () => resolve(null)
    input.click()
  })
}

/**
 * 发送系统通知
 * Tauri 环境：使用 @tauri-apps/plugin-notification
 * Web 降级：使用浏览器 Notification API
 */
export async function sendNotification(title: string, body?: string): Promise<void> {
  if (isTauri()) {
    const { sendNotification: tauriNotify } = await import('@tauri-apps/plugin-notification')
    await tauriNotify({ title, body: body || '' })
    return
  }

  // Web 降级：浏览器 Notification API
  if ('Notification' in window) {
    if (Notification.permission === 'granted') {
      new Notification(title, { body: body || '' })
    } else if (Notification.permission !== 'denied') {
      const permission = await Notification.requestPermission()
      if (permission === 'granted') {
        new Notification(title, { body: body || '' })
      }
    }
  }
  // 如果浏览器不支持 Notification，静默忽略
}

/**
 * 复制文本到剪贴板
 * Tauri 环境：使用 @tauri-apps/plugin-clipboard-manager
 * Web 降级：使用 navigator.clipboard API
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (isTauri()) {
      const { writeText } = await import('@tauri-apps/plugin-clipboard-manager')
      await writeText(text)
      return true
    }

    // Web 降级
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text)
      return true
    }

    // 最底层的降级方案
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    return true
  } catch {
    return false
  }
}

/**
 * 在外部浏览器打开链接
 * Tauri 环境：使用 @tauri-apps/plugin-opener
 * Web 降级：window.open()
 */
export async function openUrl(url: string): Promise<void> {
  if (isTauri()) {
    const { openUrl: tauriOpenUrl } = await import('@tauri-apps/plugin-opener')
    await tauriOpenUrl(url)
    return
  }

  // Web 降级
  window.open(url, '_blank', 'noopener,noreferrer')
}
