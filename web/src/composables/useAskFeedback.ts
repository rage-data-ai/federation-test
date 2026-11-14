import { ref } from 'vue'
import { addAskSuggestion, rateAskRun } from '../api'
import type { AskRating } from '../types'
import { errMessage } from '../types'

/**
 * 一条回答下面的评价和建议。评价再点一次同一个按钮就是撤销。
 *
 * @param runId   这次问数的记录号
 * @param rating  当前评价
 * @param onRated 网关确认后回传新的评价
 */
export function useAskFeedback(
  runId: () => string,
  rating: () => AskRating | null,
  onRated: (rating: AskRating | null) => void
) {
  const busy = ref(false)
  const error = ref('')
  const suggesting = ref(false)
  const text = ref('')
  const sent = ref(false)

  async function rate(value: AskRating) {
    if (busy.value) {
      return
    }
    busy.value = true
    error.value = ''
    try {
      const view = await rateAskRun(runId(), rating() === value ? null : value)
      onRated(view.rating)
    } catch (e) {
      error.value = errMessage(e)
    } finally {
      busy.value = false
    }
  }

  function toggleSuggest() {
    suggesting.value = !suggesting.value
    sent.value = false
    error.value = ''
  }

  async function submit() {
    const content = text.value.trim()
    if (!content || busy.value) {
      return
    }
    busy.value = true
    error.value = ''
    try {
      await addAskSuggestion(content, runId())
      text.value = ''
      suggesting.value = false
      sent.value = true
    } catch (e) {
      error.value = errMessage(e)
    } finally {
      busy.value = false
    }
  }

  return { busy, error, suggesting, text, sent, rate, toggleSuggest, submit }
}
