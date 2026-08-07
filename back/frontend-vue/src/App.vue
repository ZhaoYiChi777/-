<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { isTauri } from './composables/useTauri'
import SplashScreen from './components/SplashScreen.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import FirstRunWizard from './components/FirstRunWizard.vue'
import ErrorBoundary from './components/ErrorBoundary.vue'

const isReady = ref(false)
const router = useRouter()

let unlisten: (() => void) | null = null

onMounted(async () => {
  if (isTauri()) {
    try {
      const { listen } = await import('@tauri-apps/api/event')
      unlisten = await listen('open-settings', () => {
        router.push('/admin/settings')
      })
    } catch (e) {
      console.error('Failed to register open-settings listener:', e)
    }
  }
})

onUnmounted(() => {
  unlisten?.()
})
</script>

<template>
  <!-- 启动加载界面：backendStatus === 'running' 时隐藏 -->
  <SplashScreen @ready="isReady = true" />
  <FirstRunWizard />
  <UpdateDialog />
  <!-- 主界面：SplashScreen 消失后 fade-in 出现 -->
  <ErrorBoundary>
    <Transition name="page-fade" appear>
      <div class="page-container">
        <router-view v-show="isReady" />
      </div>
    </Transition>
  </ErrorBoundary>
</template>

<style>
/* 主界面 page-fade 过渡动画 */
.page-fade-enter-active {
  transition: opacity 0.3s ease;
}
.page-fade-leave-active {
  transition: opacity 0.2s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}

.page-container {
  width: 100%;
}
</style>
