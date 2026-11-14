<script setup lang="ts">
import { useAskFeedback } from '../../composables/useAskFeedback'
import type { AskRating } from '../../types'

const props = defineProps<{
  runId: string
  /** 只有给出了回答的问数才能评“有用”。 */
  answered: boolean
  rating: AskRating | null
}>()

const emit = defineEmits<{
  rated: [rating: AskRating | null]
}>()

const { busy, error, suggesting, text, sent, rate, toggleSuggest, submit } = useAskFeedback(
  () => props.runId,
  () => props.rating,
  (rating) => emit('rated', rating)
)
</script>

<template>
  <div class="feedback" role="group" aria-label="评价这次问数">
    <div class="row">
      <span v-if="answered" class="hint">这个回答有用吗？</span>
      <button
        v-if="answered"
        class="tiny"
        :class="{ chosen: rating === 'up' }"
        type="button"
        :aria-pressed="rating === 'up' ? 'true' : 'false'"
        :disabled="busy"
        @click="rate('up')"
      >
        有用
      </button>
      <button
        class="tiny"
        :class="{ chosen: rating === 'down' }"
        type="button"
        :aria-pressed="rating === 'down' ? 'true' : 'false'"
        :disabled="busy"
        @click="rate('down')"
      >
        没用
      </button>
      <button
        class="link"
        type="button"
        :aria-expanded="suggesting ? 'true' : 'false'"
        :aria-controls="`ask-suggest-${runId}`"
        @click="toggleSuggest"
      >
        提建议
      </button>
      <span v-if="sent" class="ok" role="status">建议已提交，可以在“建议”里查看处理结果</span>
    </div>

    <form v-if="suggesting" :id="`ask-suggest-${runId}`" class="suggest" @submit.prevent="submit">
      <label>
        哪里不对，或者应该怎么算
        <textarea v-model="text" rows="3" maxlength="2000" placeholder="例如：高危告警应该按 level >= 4 统计" />
      </label>
      <div class="actions">
        <button type="button" :disabled="busy" @click="toggleSuggest">取消</button>
        <button class="primary" type="submit" :disabled="busy || !text.trim()">提交建议</button>
      </div>
    </form>

    <p v-if="error" class="err" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.feedback {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.chosen {
  border-color: var(--accent-2);
  color: var(--accent-2);
  font-weight: 600;
}

.suggest {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 640px;
}

.suggest textarea {
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.err {
  margin: 0;
}
</style>
