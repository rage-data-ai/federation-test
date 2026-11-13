<script setup lang="ts">
import { SOURCE_LABEL, cell } from '../../composables/useAskStream'

defineProps<{
  clarify?: {
    question?: string
    options?: string[]
  } | null
  answer?: {
    answer?: string
    columns?: Array<{ name: string }>
    rows?: unknown[][]
    rowCount?: number
    caliber?: Array<{ source?: string; item?: string; detail?: string }>
    sql?: string
  } | null
  busy?: boolean
  canReply?: boolean
}>()

defineEmits<{
  pick: [opt: string]
}>()
</script>

<template>
  <div v-if="clarify" class="clarify">
    <p>{{ clarify.question }}</p>
    <div class="options">
      <button
        v-for="opt in clarify.options"
        :key="opt"
        type="button"
        :disabled="busy || !canReply"
        @click="$emit('pick', opt)"
      >
        {{ opt }}
      </button>
    </div>
  </div>

  <div v-if="answer" class="answer">
    <p class="conclusion">{{ answer.answer }}</p>
    <div v-if="answer.columns?.length" class="table-wrap">
      <table>
        <thead>
          <tr><th v-for="col in answer.columns" :key="col.name">{{ col.name }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, ridx) in answer.rows" :key="ridx">
            <td v-for="(value, cidx) in row" :key="cidx" :class="{ nulls: value === null }" :title="cell(value)">{{ cell(value) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="(answer.rowCount || 0) > (answer.rows?.length || 0)" class="hint small">
      共 {{ answer.rowCount }} 行，仅展示前 {{ answer.rows?.length || 0 }} 行
    </p>
    <div v-if="answer.caliber?.length" class="caliber">
      <h4>口径来源</h4>
      <ul>
        <li v-for="(c, cidx) in answer.caliber" :key="cidx">
          <span class="source" :class="c.source">{{ SOURCE_LABEL[c.source || ''] || c.source }}</span>
          <b>{{ c.item }}</b> <span class="hint">{{ c.detail }}</span>
        </li>
      </ul>
    </div>
    <details v-if="answer.sql" class="final-sql">
      <summary>最终 SQL</summary>
      <pre class="sql">{{ answer.sql }}</pre>
    </details>
  </div>
</template>

<style scoped>
.clarify,
.answer {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.clarify p {
  margin: 0 0 10px;
}

.options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.conclusion {
  margin: 0 0 10px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.small {
  margin: 6px 0 0;
  font-size: 12px;
}

.caliber h4 {
  margin: 12px 0 6px;
  font-size: 13px;
}

.caliber ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.caliber code {
  margin-right: 6px;
  font-size: 12px;
}

.source {
  display: inline-block;
  min-width: 56px;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  text-align: center;
  border: 1px solid var(--line);
}

.source.ontology { color: var(--ok); border-color: var(--ok); }
.source.inferred { color: var(--warn); border-color: var(--warn); }
.source.user { color: var(--accent-2); border-color: var(--accent-2); }

.final-sql {
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted);
}

.sql {
  margin: 4px 0;
  font-size: 12px;
}
</style>
