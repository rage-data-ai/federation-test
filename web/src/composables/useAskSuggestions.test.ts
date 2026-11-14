import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addAskSuggestion,
  deleteAskSuggestion,
  getAskRun,
  listAskSuggestions,
  updateAskSuggestion
} from '../api'
import type { AskRunView, AskSuggestion } from '../types'
import { formatTime, useAskSuggestions } from './useAskSuggestions'

vi.mock('../api', () => ({
  addAskSuggestion: vi.fn(),
  deleteAskSuggestion: vi.fn(),
  getAskRun: vi.fn(),
  listAskSuggestions: vi.fn(),
  updateAskSuggestion: vi.fn()
}))

function suggestion(patch: Partial<AskSuggestion> = {}): AskSuggestion {
  return {
    id: 1,
    runId: null,
    runQuestion: null,
    userName: 'alice',
    content: '高危应该按 level >= 4 算',
    status: 'open',
    disposition: null,
    reply: null,
    handledBy: null,
    createdAt: 1_000,
    updatedAt: 1_000,
    ...patch
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('useAskSuggestions', () => {
  beforeEach(() => {
    for (const fn of [addAskSuggestion, deleteAskSuggestion, getAskRun, listAskSuggestions, updateAskSuggestion]) {
      vi.mocked(fn).mockReset()
    }
  })

  it('loads with the chosen filters', async () => {
    vi.mocked(listAskSuggestions).mockResolvedValue([suggestion()])
    const { items, status, mineOnly, load, loading } = useAskSuggestions(() => ({ name: 'root', admin: true }))
    status.value = 'open'
    mineOnly.value = true

    await load()

    expect(listAskSuggestions).toHaveBeenCalledWith({ status: 'open', mine: true })
    expect(items.value).toHaveLength(1)
    expect(loading.value).toBe(false)
  })

  it('shows the result of the latest filter when an older request returns last', async () => {
    const slow = deferred<AskSuggestion[]>()
    vi.mocked(listAskSuggestions)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce([suggestion({ id: 2, status: 'adopted' })])
    const { items, status, load } = useAskSuggestions(() => ({ name: 'root', admin: true }))

    const first = load()
    status.value = 'adopted'
    await load()
    slow.resolve([suggestion({ id: 1 })])
    await first

    expect(items.value.map((item) => item.id)).toEqual([2])
  })

  it('reports a failed load and keeps what was on screen', async () => {
    vi.mocked(listAskSuggestions).mockResolvedValueOnce([suggestion()]).mockRejectedValueOnce(new Error('网关不可用 (500)'))
    const { items, error, load } = useAskSuggestions(() => ({ name: 'alice', admin: false }))

    await load()
    await load()

    expect(error.value).toBe('网关不可用 (500)')
    expect(items.value).toHaveLength(1)
  })

  it('adds a suggestion and reloads the list', async () => {
    vi.mocked(addAskSuggestion).mockResolvedValue(suggestion())
    vi.mocked(listAskSuggestions).mockResolvedValue([suggestion()])
    const { items, add } = useAskSuggestions(() => ({ name: 'alice', admin: false }))

    await expect(add('  高危应该按 level >= 4 算 ')).resolves.toBe(true)

    expect(addAskSuggestion).toHaveBeenCalledWith('高危应该按 level >= 4 算')
    expect(items.value).toHaveLength(1)
  })

  it('replaces the handled suggestion, and drops it when it no longer matches the filter', async () => {
    const adopted = suggestion({ status: 'adopted', disposition: 'ontology', reply: '已改', handledBy: 'root' })
    vi.mocked(listAskSuggestions).mockResolvedValue([suggestion(), suggestion({ id: 2 })])
    vi.mocked(updateAskSuggestion).mockResolvedValue(adopted)
    const { items, status, load, update } = useAskSuggestions(() => ({ name: 'root', admin: true }))

    await load()
    await update(1, { status: 'adopted', disposition: 'ontology', reply: '已改' })
    expect(items.value[0]).toEqual(adopted)

    status.value = 'open'
    await update(1, { status: 'adopted' })
    expect(items.value.map((item) => item.id)).toEqual([2])
  })

  it('surfaces a refused action without touching the list', async () => {
    vi.mocked(listAskSuggestions).mockResolvedValue([suggestion()])
    vi.mocked(deleteAskSuggestion).mockRejectedValue(new Error('建议已处理，只有管理员能删除'))
    const { items, error, busy, load, remove } = useAskSuggestions(() => ({ name: 'alice', admin: false }))

    await load()
    await expect(remove(1)).resolves.toBe(false)

    expect(error.value).toBe('建议已处理，只有管理员能删除')
    expect(items.value).toHaveLength(1)
    expect(busy.value).toBe(false)
  })

  it('removes a deleted suggestion and closes its run detail', async () => {
    const linked = suggestion({ runId: 'run-1', runQuestion: '最近一天高危告警最多的资产' })
    vi.mocked(listAskSuggestions).mockResolvedValue([linked])
    vi.mocked(getAskRun).mockResolvedValue({ run: { id: 'run-1' }, rating: null } as AskRunView)
    vi.mocked(deleteAskSuggestion).mockResolvedValue({ success: true })
    const { items, detail, load, toggleRun, remove } = useAskSuggestions(() => ({ name: 'alice', admin: false }))

    await load()
    await toggleRun(linked)
    expect(detail.value?.view?.run.id).toBe('run-1')

    await remove(1)
    expect(items.value).toEqual([])
    expect(detail.value).toBeNull()
  })

  it('opens the linked run, shows why it cannot be read, and closes on a second click', async () => {
    const linked = suggestion({ runId: 'run-1' })
    vi.mocked(getAskRun).mockRejectedValue(new Error('问数记录不存在：run-1'))
    const { detail, toggleRun } = useAskSuggestions(() => ({ name: 'alice', admin: false }))

    await toggleRun(linked)
    expect(detail.value).toEqual({ suggestionId: 1, loading: false, error: '问数记录不存在：run-1', view: null })

    await toggleRun(linked)
    expect(detail.value).toBeNull()

    await toggleRun(suggestion({ id: 3 }))
    expect(getAskRun).toHaveBeenCalledTimes(1)
  })

  it('offers edit to the author while open, and delete to the author or an admin', () => {
    const alice = useAskSuggestions(() => ({ name: 'alice', admin: false }))
    const bob = useAskSuggestions(() => ({ name: 'bob', admin: false }))
    const root = useAskSuggestions(() => ({ name: 'root', admin: true }))
    const open = suggestion()
    const handled = suggestion({ status: 'rejected' })

    expect(alice.canEdit(open)).toBe(true)
    expect(alice.canEdit(handled)).toBe(false)
    expect(alice.canDelete(handled)).toBe(false)
    expect(bob.canEdit(open)).toBe(false)
    expect(bob.canDelete(open)).toBe(false)
    expect(root.canEdit(open)).toBe(false)
    expect(root.canDelete(handled)).toBe(true)
  })
})

describe('formatTime', () => {
  it('renders local time to the minute', () => {
    expect(formatTime(new Date(2026, 10, 14, 9, 5).getTime())).toBe('2026-11-14 09:05')
  })
})
