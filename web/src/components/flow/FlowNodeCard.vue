<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import { computed } from 'vue'
import { catalogItem } from '../../flow/nodeCatalog'
import type { DagNodeData } from '../../flow/graphAdapter'

const props = defineProps<{
  id: string
  data?: Partial<DagNodeData>
  selected?: boolean
}>()

const data = computed<DagNodeData>(() => ({
  flowType: props.data?.flowType || 'llm_chat',
  title: props.data?.title || props.id,
  config: props.data?.config || {},
  runStatus: props.data?.runStatus || 'idle'
}))
const meta = computed(() => catalogItem(data.value.flowType))
const runStatus = computed(() => data.value.runStatus || 'idle')
</script>

<template>
  <div
    class="card"
    :class="{
      selected,
      start: data.flowType === 'start',
      end: data.flowType === 'end',
      running: runStatus === 'running',
      ok: runStatus === 'ok',
      errored: runStatus === 'error'
    }"
    :style="{ '--node-accent': meta.color }"
  >
    <Handle v-if="data.flowType !== 'start'" id="in" type="target" :position="Position.Left" />
    <div class="kind">
      {{ meta.title }}
      <em v-if="runStatus === 'running'">运行中</em>
      <em v-else-if="runStatus === 'ok'">完成</em>
      <em v-else-if="runStatus === 'error'">出错</em>
    </div>
    <div class="title">{{ data.title || meta.title }}</div>
    <Handle v-if="data.flowType !== 'end'" id="out" type="source" :position="Position.Right" />
  </div>
</template>

<style scoped>
.card {
  min-width: 148px;
  max-width: 200px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: inset 3px 0 0 var(--node-accent, var(--accent));
  color: var(--text);
}

.card.selected {
  border-color: var(--accent);
}

.card.running {
  border-color: var(--accent);
  box-shadow:
    inset 3px 0 0 var(--node-accent, var(--accent)),
    0 0 0 1px color-mix(in srgb, var(--accent) 45%, transparent);
}

.card.ok {
  border-color: var(--ok, #2a9d6a);
}

.card.errored {
  border-color: var(--err, #c44);
}

.kind {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--muted);
}

.kind em {
  font-style: normal;
  font-size: 10px;
  color: var(--accent);
}

.card.ok .kind em {
  color: var(--ok, #2a9d6a);
}

.card.errored .kind em {
  color: var(--err, #c44);
}

.title {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
}

.desc {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.35;
}

:deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid var(--panel);
  background: var(--node-accent, var(--accent));
}
</style>
