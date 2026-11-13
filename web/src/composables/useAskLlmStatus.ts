import { computed, inject, onActivated, ref, watch, type Ref } from 'vue'
import { llmStatus } from '../api'

export type LlmStatus = {
  configured?: boolean
  protocol?: string
  model?: string
  [key: string]: unknown
}

/**
 * LLM readiness for AskView: status fetch, injects, and ready flag.
 */
export function useAskLlmStatus() {
  const status = ref<LlmStatus | null>(null)
  const statusError = ref('')
  const openLlmSettings = inject<() => void>('openLlmSettings', () => {})
  const llmRevision = inject<Ref<number>>('llmRevision', ref(0))
  const ready = computed(() => !!status.value?.configured)

  async function loadStatus() {
    try {
      status.value = (await llmStatus()) as LlmStatus
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
    openLlmSettings,
    loadStatus
  }
}
