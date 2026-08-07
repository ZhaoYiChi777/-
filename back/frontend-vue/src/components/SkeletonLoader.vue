<template>
  <div class="skeleton-loader" :style="{ width, height }">
    <div class="skeleton-shimmer"></div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  width?: string
  height?: string
}>(), {
  width: '100%',
  height: '16px',
})
</script>

<style scoped>
.skeleton-loader {
  position: relative;
  overflow: hidden;
  background: #E2E8F0;
  border-radius: 4px;
}

.skeleton-shimmer {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
</style>
