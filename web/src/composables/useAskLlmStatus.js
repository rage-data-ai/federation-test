import { computed, inject, onActivated, ref, watch } from 'vue'
import { llmStatus } from '../api'

/**
 * LLM readiness for AskView: status fetch, injects, and ready flag.
 */
export function useAskLlmStatus() {
  const status = ref(null)
  const statusError = ref('')
  const openLlmSettings = inject('openLlmSettings', () => {})
  const llmRevision = inject('llmRevision', ref(0))
  const ready = computed(() => status.value?.configured)

  async function loadStatus() {
    try {
      status.value = await llmStatus()
      statusError.value = ''
    } catch (e) {
      statusError.value = e.message
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
