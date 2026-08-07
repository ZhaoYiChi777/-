/**
 * useBackend - 后端状态管理 composable
 * 封装 Tauri invoke 调用，管理 Spring Boot 后端的生命周期与状态轮询
 */

import { ref, onMounted, onUnmounted } from 'vue'
import { isTauri } from './useTauri'

/** 后端状态 */
export type BackendStatus = 'running' | 'stopped' | 'unknown'

/** 全局后端状态（多个组件共享） */
const backendStatus = ref<BackendStatus>('unknown')
let pollTimer: ReturnType<typeof setInterval> | null = null
let pollRefCount = 0

/**
 * 调用 Tauri invoke 获取后端状态
 * 非 Tauri 环境：通过 HTTP 健康检查端点判断
 */
async function checkBackendStatus(): Promise<BackendStatus> {
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      const status = await invoke<string>('backend_status')
      return status === 'running' ? 'running' : 'stopped'
    } catch {
      return 'unknown'
    }
  }

  // Web 降级：通过 Vite 代理调用 /api/health
  try {
    const baseUrl = isTauri() ? 'http://localhost:8080' : ''
    const resp = await fetch(`${baseUrl}/api/health`, { method: 'GET', signal: AbortSignal.timeout(3000) })
    return resp.ok ? 'running' : 'stopped'
  } catch {
    return 'unknown'
  }
}

/**
 * 启动后端
 */
async function startBackend(): Promise<boolean> {
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('start_backend')
      return true
    } catch (e) {
      console.error('[useBackend] Failed to start backend:', e)
      return false
    }
  }
  // Web 环境无法直接启动后端
  return false
}

/**
 * 停止后端
 */
async function stopBackend(): Promise<boolean> {
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('stop_backend')
      backendStatus.value = 'stopped'
      return true
    } catch (e) {
      console.error('[useBackend] Failed to stop backend:', e)
      return false
    }
  }
  return false
}

/**
 * 开始轮询后端状态（每 5 秒）
 * 使用引用计数，多个组件同时使用时只启动一个轮询
 */
function startPolling() {
  pollRefCount++
  if (pollTimer !== null) return

  const poll = async () => {
    backendStatus.value = await checkBackendStatus()
  }

  // 立即执行一次
  poll()

  pollTimer = setInterval(poll, 5000)
}

/**
 * 停止轮询（引用计数归零时真正停止）
 */
function stopPolling() {
  pollRefCount = Math.max(0, pollRefCount - 1)
  if (pollRefCount === 0 && pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * composable 主函数
 * 在组件的 setup() 中调用，自动在 onMounted 时开始轮询，onUnmounted 时停止
 */
export function useBackend() {
  onMounted(() => {
    startPolling()
  })

  onUnmounted(() => {
    stopPolling()
  })

  return {
    backendStatus,
    checkBackendStatus,
    startBackend,
    stopBackend,
  }
}
