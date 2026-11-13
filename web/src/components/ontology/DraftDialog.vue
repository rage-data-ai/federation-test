<script setup>
import { DRAFT_STEPS, qualified } from '../../composables/useOntologyDraft'

defineProps({
  show: { type: Boolean, required: true },
  drafting: { type: Boolean, default: false },
  draftSteps: { type: Array, default: () => DRAFT_STEPS },
  draftReached: { type: Function, required: true },
  draftSubject: { type: String, default: '' },
  draftQuery: { type: String, default: '' },
  draftNote: { type: String, default: '' },
  draftTables: { type: Array, default: () => [] },
  draftCandidates: { type: Array, default: () => [] },
  draftLog: { type: Array, default: () => [] },
  draftError: { type: String, default: '' },
  draftResult: { type: Object, default: null },
  draftStep: { type: String, default: '' },
  draftDetail: { type: String, default: '' },
  draftElapsed: { type: Number, default: 0 },
  takenElsewhere: { type: Function, required: true },
  unassignedLabel: { type: String, required: true }
})

const emit = defineEmits([
  'close',
  'update:draftSubject',
  'update:draftQuery',
  'update:draftNote',
  'toggle-table',
  'reselect',
  'finish',
  'run'
])
</script>

<template>
  <div v-if="show" class="mask" @click.self="!drafting && emit('close')">
    <div class="dialog draft">
      <h3>大模型起草</h3>
      <ol class="steps">
        <li v-for="step in draftSteps" :key="step.key" :class="{ on: draftReached(step.key) }">{{ step.label }}</li>
      </ol>
      <template v-if="!drafting && !draftLog.length">
        <label>
          主题域（必填）
          <input
            :value="draftSubject"
            list="onto-subjects"
            placeholder="选一个已有的主题域，或输入新名字，例如 安全日志"
            @input="emit('update:draftSubject', $event.target.value)"
          />
        </label>
        <p class="hint">本次勾选的表都归到这个主题域，起草时只会和这个主题域里已有的概念建立关系。</p>
        <input
          :value="draftQuery"
          type="search"
          placeholder="过滤表名"
          @input="emit('update:draftQuery', $event.target.value)"
        />
        <div class="picker">
          <label v-for="t in draftCandidates" :key="qualified(t)" class="check" :class="{ off: takenElsewhere(qualified(t)) }">
            <input
              type="checkbox"
              :checked="draftTables.includes(qualified(t))"
              :disabled="!!takenElsewhere(qualified(t)) && !draftTables.includes(qualified(t))"
              @change="emit('toggle-table', qualified(t))"
            />
            {{ qualified(t) }} <em>{{ (t.columns || []).length }} 列</em>
            <em v-if="takenElsewhere(qualified(t))" class="taken">已在{{ takenElsewhere(qualified(t)).subject ? `主题域 ${takenElsewhere(qualified(t)).subject}` : unassignedLabel }}</em>
          </label>
          <p v-if="!draftCandidates.length" class="hint">没有可选的表，请先在左侧数据源中采集元数据。</p>
        </div>
        <p class="hint">已选 {{ draftTables.length }} 张：{{ draftTables.join('，') }}</p>
        <label>
          补充说明（可选，如业务背景、哪些列是码值）
          <textarea
            :value="draftNote"
            rows="3"
            @input="emit('update:draftNote', $event.target.value)"
          />
        </label>
        <p class="hint">开始后会逐步列出每一段：读了哪些表、写入了几个概念和属性、哪一段被跳过。</p>
      </template>
      <p v-else class="hint">主题域 {{ draftSubject.trim() }}，已选 {{ draftTables.length }} 张：{{ draftTables.join('，') }}</p>
      <ol v-if="draftLog.length" class="draft-log">
        <li v-for="(line, index) in draftLog" :key="index" :class="line.status">
          <span>{{ line.text }}</span>
          <ul v-if="line.notes.length">
            <li v-for="note in line.notes" :key="note">{{ note }}</li>
          </ul>
        </li>
      </ol>
      <p v-if="draftError" class="err">{{ draftError }}</p>
      <div v-if="draftResult" class="ok">
        已写入：概念 {{ draftResult.concepts }}、属性 {{ draftResult.attributes }}、关系 {{ draftResult.relations }}、
        码值 {{ draftResult.codes }}、派生概念 {{ draftResult.definedConcepts }}
      </div>
      <div class="actions">
        <button v-if="!drafting && draftLog.length" type="button" @click="emit('reselect')">重新选择表</button>
        <button type="button" :disabled="drafting" @click="emit('finish')">{{ draftStep === 'done' ? '在图中查看' : '关闭' }}</button>
        <button class="primary" type="button" :disabled="drafting || !draftTables.length || !draftSubject.trim()" @click="emit('run')">
          {{ drafting ? `${draftDetail || '起草中…'} · ${draftElapsed}s` : '开始起草' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.picker .check.off {
  color: var(--muted);
}

.picker .taken {
  color: var(--warn);
}

.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.steps li {
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
}

.steps li.on {
  color: var(--text);
  border-color: var(--accent);
  background: var(--panel-2);
}

.picker em {
  font-style: normal;
  font-weight: 400;
  color: var(--muted);
  font-size: 11px;
}

.draft {
  width: min(680px, 100%);
  max-height: 88vh;
  overflow: auto;
}

.draft > * {
  flex-shrink: 0;
}

.draft .hint {
  margin: 0;
}

.picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 120px;
  max-height: 34vh;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.picker .check {
  color: var(--text);
  font-size: 13px;
  gap: 8px;
}

.draft-log {
  margin: 0;
  padding: 8px 10px;
  max-height: 42vh;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  list-style: none;
  font-size: 13px;
}

.draft-log li {
  padding: 4px 0;
  border-bottom: 1px solid var(--line);
}

.draft-log li:last-child {
  border-bottom: 0;
}

.draft-log li.start {
  color: var(--text);
}

.draft-log li.done {
  color: var(--muted);
}

.draft-log ul {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--warn);
  font-size: 12px;
}
</style>
