import { beforeEach, describe, expect, it, vi } from 'vitest'
import { addAskSuggestion, rateAskRun } from '../api'
import type { AskRating, AskRunView } from '../types'
import { useAskFeedback } from './useAskFeedback'

vi.mock('../api', () => ({ addAskSuggestion: vi.fn(), rateAskRun: vi.fn() }))

function view(rating: AskRating | null): AskRunView {
  return { run: { id: 'run-1' } as AskRunView['run'], rating }
}

function feedback(initial: AskRating | null = null) {
  const state = { rating: initial }
  const api = useAskFeedback(
    () => 'run-1',
    () => state.rating,
    (rating) => {
      state.rating = rating
    }
  )
  return { state, ...api }
}

describe('useAskFeedback', () => {
  beforeEach(() => {
    vi.mocked(rateAskRun).mockReset()
    vi.mocked(addAskSuggestion).mockReset()
  })

  it('rates the run and reports what the gateway stored', async () => {
    vi.mocked(rateAskRun).mockResolvedValue(view('up'))
    const { state, rate } = feedback()

    await rate('up')

    expect(rateAskRun).toHaveBeenCalledWith('run-1', 'up')
    expect(state.rating).toBe('up')
  })

  it('withdraws the rating when the same button is pressed again', async () => {
    vi.mocked(rateAskRun).mockResolvedValue(view(null))
    const { state, rate } = feedback('down')

    await rate('down')

    expect(rateAskRun).toHaveBeenCalledWith('run-1', null)
    expect(state.rating).toBeNull()
  })

  it('keeps the old rating and shows the reason when the gateway refuses', async () => {
    vi.mocked(rateAskRun).mockRejectedValue(new Error('只有给出了回答的问数才能评为有用'))
    const { state, rate, error, busy } = feedback('down')

    await rate('up')

    expect(state.rating).toBe('down')
    expect(error.value).toBe('只有给出了回答的问数才能评为有用')
    expect(busy.value).toBe(false)
  })

  it('submits a trimmed suggestion against the run and closes the box', async () => {
    vi.mocked(addAskSuggestion).mockResolvedValue({ id: 1 } as Awaited<ReturnType<typeof addAskSuggestion>>)
    const { toggleSuggest, text, submit, suggesting, sent } = feedback()

    toggleSuggest()
    text.value = '  高危应该按 level >= 4 算  '
    await submit()

    expect(addAskSuggestion).toHaveBeenCalledWith('高危应该按 level >= 4 算', 'run-1')
    expect(suggesting.value).toBe(false)
    expect(sent.value).toBe(true)
    expect(text.value).toBe('')
  })

  it('does not submit blank text and keeps the draft when the gateway fails', async () => {
    const { toggleSuggest, text, submit, suggesting, sent, error } = feedback()
    toggleSuggest()

    text.value = '   '
    await submit()
    expect(addAskSuggestion).not.toHaveBeenCalled()

    vi.mocked(addAskSuggestion).mockRejectedValue(new Error('建议内容不能超过 2000 字'))
    text.value = '太长的建议'
    await submit()

    expect(error.value).toBe('建议内容不能超过 2000 字')
    expect(text.value).toBe('太长的建议')
    expect(suggesting.value).toBe(true)
    expect(sent.value).toBe(false)
  })
})
