<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getAskConversation, listAskConversations } from '../../api'
import type { AskConversationSummary, AskRunView } from '../../types'
import { errMessage } from '../../types'

const props = defineProps<{
  activeId: string
  /** 问数刚结束时由父组件递增，用来刷新列表。 */
  revision: number
}>()

const emit = defineEmits<{
  select: [views: AskRunView[], conversationId: string]
  create: []
}>()

const items = ref<AskConversationSummary[]>([])
const loading = ref(false)
const opening = ref('')
const error = ref('')

onMounted(load)
watch(() => props.revision, load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await listAskConversations()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    loading.value = false
  }
}

async function open(item: AskConversationSummary) {
  if (opening.value || item.conversationId === props.activeId) {
    return
  }
  opening.value = item.conversationId
  error.value = ''
  try {
    const views = await getAskConversation(item.conversationId)
    emit('select', views, item.conversationId)
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    opening.value = ''
  }
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

const STATUS_HINT: Record<string, string> = {
  answered: '',
  clarify: '待澄清',
  error: '出错'
}

defineExpose({ load })
</script>

<template>
  <aside class="history" aria-label="对话历史">
    <div class="top">
      <h2>历史</h2>
      <button class="tiny" type="button" title="新对话" @click="emit('create')">新对话</button>
    </div>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <p v-else-if="loading && !items.length" class="hint" role="status">加载中…</p>
    <p v-else-if="!items.length" class="hint empty">还没有对话</p>
    <ul v-else class="list" aria-label="对话列表">
      <li v-for="item in items" :key="item.conversationId">
        <button
          type="button"
          class="item"
          :class="{ active: item.conversationId === activeId, busy: opening === item.conversationId }"
          :aria-current="item.conversationId === activeId ? 'true' : undefined"
          :disabled="!!opening"
          @click="open(item)"
        >
          <span class="title">{{ item.title }}</span>
          <span class="meta">
            <span v-if="STATUS_HINT[item.lastStatus]" class="state">{{ STATUS_HINT[item.lastStatus] }}</span>
            <span>{{ item.runCount }} 问 · {{ formatTime(item.updatedAt) }}</span>
          </span>
        </button>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 220px;
  flex: none;
  min-height: 0;
  padding: 12px 10px 16px;
  border-right: 1px solid var(--line);
  background: var(--panel);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px;
}

h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  text-align: left;
}

.item:hover {
  background: var(--panel-2);
}

.item.active {
  background: var(--panel-2);
  box-shadow: inset 2px 0 0 var(--accent);
}

.item.busy {
  opacity: 0.6;
}

.title {
  display: -webkit-box;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  color: var(--muted);
}

.state {
  color: var(--warn);
}

.empty,
.err {
  margin: 8px 4px;
  font-size: 12px;
}

@media (max-width: 840px) {
  .history {
    width: 168px;
  }
}
</style>
