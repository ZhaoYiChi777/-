import { ref } from 'vue'

export interface ErrorInfo {
  error: Error
  context?: string
  timestamp: number
}

export const currentError = ref<ErrorInfo | null>(null)

export function reportError(error: Error, context?: string) {
  currentError.value = {
    error,
    context,
    timestamp: Date.now(),
  }
}

export function clearError() {
  currentError.value = null
}
