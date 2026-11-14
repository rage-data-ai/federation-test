<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch, type Ref } from 'vue'
import {
  createAskKnowledge,
  deleteAskKnowledge,
  listAskKnowledge,
  updateAskKnowledge
} from '../../api'
import { formatTime } from '../../composables/useAskSuggestions'
import type { AskKnowledge, AskKnowledgeStatus, AuthMe } from '../../types'
import { errMessage } from '../../types'

const STATUS_LABEL: Record<AskKnowledgeStatus, string> = {
  pending: '待审核',
  approved: '已通过',
  rejected: '已驳回'
}

const authUser = inject<Ref<AuthMe | null>>('authUser', ref(null))
const admin = computed(() => !!authUser.value?.roles?.includes('ADMIN'))

const items = ref<AskKnowledge[]>([])
const status = ref<AskKnowledgeStatus | ''>('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
let latest = 0

const draft = reactive({ question: '', answer: '', sql: '' })
const reviewing = reactive<{ id: number; status: AskKnowledgeStatus; note: string }>({
  id: 0,
  status: 'approved',
  note: ''
})

onMounted(load)
watch(status, load)
watch(authUser, load)

async function load() {
  const ticket = ++latest
  loading.value = true
  try {
    const list = await listAskKnowledge(status.value)
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

async function submitDraft() {
  const question = draft.question.trim()
  const answer = draft.answer.trim()
  if (!question || !answer || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await createAskKnowledge({
      question,
      answer,
      sql: draft.sql.trim() || null
    })
    draft.question = ''
    draft.answer = ''
    draft.sql = ''
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

function startReview(item: AskKnowledge, next: AskKnowledgeStatus) {
  reviewing.id = item.id
  reviewing.status = next
  reviewing.note = item.reviewNote ?? ''
}

async function saveReview() {
  if (!reviewing.id || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await updateAskKnowledge(reviewing.id, {
      status: reviewing.status,
      reviewNote: reviewing.note.trim() || null
    })
    reviewing.id = 0
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

function canDelete(item: AskKnowledge): boolean {
  if (admin.value) {
    return true
  }
  return item.status === 'pending' && item.createdBy === (authUser.value?.name ?? '')
}

async function removeItem(item: AskKnowledge) {
  if (!confirm(`删除知识「${item.question.slice(0, 40)}」？`)) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await deleteAskKnowledge(item.id)
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="knowledge">
    <form class="new" aria-label="新增知识" @submit.prevent="submitDraft">
      <label>
        问题
        <textarea v-model="draft.question" rows="2" maxlength="2000" placeholder="业务问法，作为检索正本" />
      </label>
      <label>
        答案
        <textarea v-model="draft.answer" rows="2" maxlength="8000" placeholder="自然语言结论" />
      </label>
      <label>
        SQL（可选）
        <textarea v-model="draft.sql" rows="2" maxlength="8000" placeholder="对应查询 SQL" />
      </label>
      <button class="primary" type="submit" :disabled="busy || !draft.question.trim() || !draft.answer.trim()">
        提交知识
      </button>
    </form>

    <div class="filters">
      <label class="inline">
        状态
        <select v-model="status">
          <option value="">全部</option>
          <option v-for="(label, key) in STATUS_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <button type="button" :disabled="loading" @click="load">刷新</button>
      <span class="hint" role="status">{{ loading ? '加载中…' : `${items.length} 条` }}</span>
    </div>

    <p v-if="error" class="err banner" role="alert">{{ error }}</p>

    <p v-if="!loading && !items.length && !error" class="empty">
      {{ status ? '没有符合条件的知识' : '还没有知识' }}
    </p>

    <ul class="list" aria-label="知识列表">
      <li v-for="item in items" :key="item.id" class="item">
        <div class="meta">
          <span class="state" :class="item.status">{{ STATUS_LABEL[item.status] }}</span>
          <span class="hint">{{ item.createdBy }} · {{ formatTime(item.createdAt) }}</span>
          <span v-if="item.sourceRunId" class="hint">来源问数 {{ item.sourceRunId }}</span>
        </div>

        <p class="content"><b>问</b> {{ item.question }}</p>
        <p class="content"><b>答</b> {{ item.answerPair.answer }}</p>
        <pre v-if="item.answerPair.sql" class="sql">{{ item.answerPair.sql }}</pre>

        <p v-if="item.reviewNote" class="reply">
          <span class="hint">{{ item.reviewedBy || '管理员' }} 审核：</span>{{ item.reviewNote }}
        </p>

        <form v-if="reviewing.id === item.id" class="inline-form" @submit.prevent="saveReview">
          <label>
            审核说明（提出人能看到）
            <textarea v-model="reviewing.note" rows="2" maxlength="1000" />
          </label>
          <div class="actions">
            <button type="button" :disabled="busy" @click="reviewing.id = 0">取消</button>
            <button class="primary" type="submit" :disabled="busy">
              {{ reviewing.status === 'approved' ? '确认通过' : '确认驳回' }}
            </button>
          </div>
        </form>

        <div v-else class="ops">
          <template v-if="admin">
            <button
              v-if="item.status !== 'approved'"
              class="tiny"
              type="button"
              @click="startReview(item, 'approved')"
            >
              通过
            </button>
            <button
              v-if="item.status !== 'rejected'"
              class="tiny"
              type="button"
              @click="startReview(item, 'rejected')"
            >
              驳回
            </button>
            <button
              v-if="item.status !== 'pending'"
              class="tiny"
              type="button"
              @click="startReview(item, 'pending')"
            >
              重新打开
            </button>
          </template>
          <button
            v-if="canDelete(item)"
            class="tiny danger"
            type="button"
            :disabled="busy"
            @click="removeItem(item)"
          >
            删除
          </button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.knowledge {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px 24px;
}

.knowledge > * {
  max-width: 980px;
  margin-left: auto;
  margin-right: auto;
}

.new {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.new > button {
  align-self: flex-start;
}

textarea {
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  margin-bottom: 12px;
  font-size: 12px;
}

.inline {
  flex-direction: row;
  align-items: center;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0;
  list-style: none;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.meta,
.ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.state {
  padding: 1px 8px;
  border: 1px solid currentColor;
  border-radius: 10px;
  font-size: 11px;
}

.state.pending { color: var(--warn); }
.state.approved { color: var(--ok); }
.state.rejected { color: var(--muted); }

.content,
.reply {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.reply {
  font-size: 13px;
}

.sql {
  margin: 0;
  font-size: 12px;
}

.inline-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.actions {
  display: flex;
  gap: 8px;
}
</style>
