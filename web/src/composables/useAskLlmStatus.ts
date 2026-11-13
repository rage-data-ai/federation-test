import { computed, inject, onActivated, ref, watch, type Ref } from 'vue'
import { llmConfig } from '../api'
import type { LlmConfig, LlmStatus } from '../types'

export type { LlmStatus }

/**
 * LLM readiness for AskView: status fetch, injects, and ready flag.
 */
export function useAskLlmStatus() {
  const status = ref<LlmConfig | null>(null)
  const statusError = ref('')
  const openLlmSettings = inject<() => void>('openLlmSettings', () => {})
  const llmRevision = inject<Ref<number>>('llmRevision', ref(0))
  const ready = computed(() => !!(status.value?.configured && status.value?.apiKeySet))
  const needsKey = computed(() => !!(status.value?.configured && !status.value?.apiKeySet))

  async function loadStatus() {
    try {
      status.value = await llmConfig()
      statusError.value = ''
    } catch (e) {
      statusError.value = e instanceof Error ? e.message : String(e)
    }
  }

  watch(llmRevision, loadStatus)
  onActivated(loadStatus)

  return {
    status,
    statusError,
    ready,
    needsKey,
    openLlmSettings,
    loadStatus
  }
}
