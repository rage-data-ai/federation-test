import { ref } from 'vue'
import {
  addAskSuggestion,
  deleteAskSuggestion,
  getAskRun,
  listAskSuggestions,
  updateAskSuggestion
} from '../api'
import type {
  AskRunView,
  AskSuggestion,
  AskSuggestionDisposition,
  AskSuggestionPatch,
  AskSuggestionStatus
} from '../types'
import { errMessage } from '../types'

export const STATUS_LABEL: Record<AskSuggestionStatus, string> = {
  open: '待处理',
  adopted: '已采纳',
  rejected: '未采纳'
}

export const DISPOSITION_LABEL: Record<AskSuggestionDisposition, string> = {
  knowledge: '知识库',
  prompt: '提示词',
  ontology: '本体'
}

export const RUN_STATUS_LABEL: Record<string, string> = {
  answered: '已回答',
  clarify: '反问澄清',
  error: '失败'
}

export type Identity = { name: string; admin: boolean }

export type RunDetail = {
  suggestionId: number
  loading: boolean
  error: string
  view: AskRunView | null
}

export function formatTime(ms: number): string {
  const date = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 建议列表：筛选、提交、修改、处置、删除。谁能做什么以网关为准，
 * 这里的 canEdit / canDelete 只决定按钮显不显示。
 */
export function useAskSuggestions(identity: () => Identity) {
  const items = ref<AskSuggestion[]>([])
  const status = ref<AskSuggestionStatus | ''>('')
  const mineOnly = ref(false)
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const detail = ref<RunDetail | null>(null)
  let latest = 0

  async function load() {
    const ticket = ++latest
    loading.value = true
    try {
      const list = await listAskSuggestions({ status: status.value, mine: mineOnly.value })
      if (ticket === latest) {
        items.value = list
        error.value = ''
      }
    } catch (e) {
      if (ticket === latest) {
        error.value = errMessage(e)
      }
    } finally {
      if (ticket === latest) {
        loading.value = false
      }
    }
  }

  async function act(action: () => Promise<void>): Promise<boolean> {
    if (busy.value) {
      return false
    }
    busy.value = true
    error.value = ''
    try {
      await action()
      return true
    } catch (e) {
      error.value = errMessage(e)
      return false
    } finally {
      busy.value = false
    }
  }

  function add(content: string) {
    return act(async () => {
      await addAskSuggestion(content.trim())
      await load()
    })
  }

  function update(id: number, patch: AskSuggestionPatch) {
    return act(async () => {
      const saved = await updateAskSuggestion(id, patch)
      items.value = items.value
        .map((item) => (item.id === id ? saved : item))
        .filter((item) => !status.value || item.status === status.value)
    })
  }

  function remove(id: number) {
    return act(async () => {
      await deleteAskSuggestion(id)
      items.value = items.value.filter((item) => item.id !== id)
      if (detail.value?.suggestionId === id) {
        detail.value = null
      }
    })
  }

  async function toggleRun(item: AskSuggestion) {
    if (!item.runId || detail.value?.suggestionId === item.id) {
      detail.value = null
      return
    }
    const opened: RunDetail = { suggestionId: item.id, loading: true, error: '', view: null }
    detail.value = opened
    try {
      const view = await getAskRun(item.runId)
      if (detail.value?.suggestionId === item.id) {
        detail.value = { ...opened, loading: false, view }
      }
    } catch (e) {
      if (detail.value?.suggestionId === item.id) {
        detail.value = { ...opened, loading: false, error: errMessage(e) }
      }
    }
  }

  function canEdit(item: AskSuggestion): boolean {
    return item.status === 'open' && item.userName === identity().name
  }

  function canDelete(item: AskSuggestion): boolean {
    return identity().admin || canEdit(item)
  }

  return {
    items,
    status,
    mineOnly,
    loading,
    busy,
    error,
    detail,
    load,
    add,
    update,
    remove,
    toggleRun,
    canEdit,
    canDelete
  }
}
