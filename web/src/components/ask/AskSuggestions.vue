<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch, type Ref } from 'vue'
import {
  DISPOSITION_LABEL,
  RUN_STATUS_LABEL,
  STATUS_LABEL,
  formatTime,
  useAskSuggestions
} from '../../composables/useAskSuggestions'
import type { AskSuggestion, AskSuggestionDisposition, AskSuggestionStatus, AuthMe } from '../../types'

const authUser = inject<Ref<AuthMe | null>>('authUser', ref(null))
const admin = computed(() => !!authUser.value?.roles?.includes('ADMIN'))

const { items, status, mineOnly, loading, busy, error, detail, load, add, update, remove, toggleRun, canEdit, canDelete } =
  useAskSuggestions(() => ({ name: authUser.value?.name ?? '', admin: admin.value }))

const draft = ref('')
const editing = reactive({ id: 0, content: '' })
const handling = reactive<{
  id: number
  status: AskSuggestionStatus
  disposition: AskSuggestionDisposition | ''
  reply: string
}>({ id: 0, status: 'adopted', disposition: '', reply: '' })

onMounted(load)
watch([status, mineOnly], load)
watch(authUser, load)

async function submitDraft() {
  if (await add(draft.value)) {
    draft.value = ''
  }
}

function startEdit(item: AskSuggestion) {
  handling.id = 0
  editing.id = item.id
  editing.content = item.content
}

async function saveEdit() {
  if (await update(editing.id, { content: editing.content })) {
    editing.id = 0
  }
}

function startHandle(item: AskSuggestion, next: AskSuggestionStatus) {
  editing.id = 0
  handling.id = item.id
  handling.status = next
  handling.disposition = item.disposition ?? ''
  handling.reply = item.reply ?? ''
}

async function saveHandle() {
  const saved = await update(handling.id, {
    status: handling.status,
    disposition: handling.status === 'adopted' ? handling.disposition : '',
    reply: handling.reply
  })
  if (saved) {
    handling.id = 0
  }
}

async function removeItem(item: AskSuggestion) {
  if (confirm(`删除 ${item.userName} 的这条建议？`)) {
    await remove(item.id)
  }
}
</script>

<template>
  <div class="suggestions">
    <form class="new" aria-label="提交建议" @submit.prevent="submitDraft">
      <label>
        对问数的建议
        <textarea
          v-model="draft"
          rows="2"
          maxlength="2000"
          placeholder="口径不对、结果不好懂、希望支持的问法……针对某次回答的建议，可以直接在那条回答下面提"
        />
      </label>
      <button class="primary" type="submit" :disabled="busy || !draft.trim()">提交建议</button>
    </form>

    <div class="filters">
      <label class="inline">
        状态
        <select v-model="status">
          <option value="">全部</option>
          <option v-for="(label, key) in STATUS_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label v-if="admin" class="check">
        <input v-model="mineOnly" type="checkbox" />
        只看我提的
      </label>
      <button type="button" :disabled="loading" @click="load">刷新</button>
      <span class="hint" role="status">{{ loading ? '加载中…' : `${items.length} 条` }}</span>
    </div>

    <p v-if="error" class="err banner" role="alert">{{ error }}</p>

    <p v-if="!loading && !items.length && !error" class="empty">
      {{ status || mineOnly ? '没有符合条件的建议' : '还没有建议' }}
    </p>

    <ul class="list" aria-label="建议列表">
      <li v-for="item in items" :key="item.id" class="item">
        <div class="meta">
          <span class="state" :class="item.status">{{ STATUS_LABEL[item.status] }}</span>
          <span v-if="item.disposition" class="hint">去向：{{ DISPOSITION_LABEL[item.disposition] }}</span>
          <span class="hint">{{ item.userName }} · {{ formatTime(item.createdAt) }}</span>
        </div>

        <form v-if="editing.id === item.id" class="inline-form" @submit.prevent="saveEdit">
          <label>
            修改建议
            <textarea v-model="editing.content" rows="3" maxlength="2000" />
          </label>
          <div class="actions">
            <button type="button" :disabled="busy" @click="editing.id = 0">取消</button>
            <button class="primary" type="submit" :disabled="busy || !editing.content.trim()">保存</button>
          </div>
        </form>
        <p v-else class="content">{{ item.content }}</p>

        <p v-if="item.runId" class="run-link">
          <span class="hint">针对问数：</span>
          <button
            class="link"
            type="button"
            :aria-expanded="detail?.suggestionId === item.id ? 'true' : 'false'"
            @click="toggleRun(item)"
          >
            {{ item.runQuestion || item.runId }}
          </button>
        </p>
        <div v-if="detail?.suggestionId === item.id" class="run">
          <p v-if="detail.loading" class="hint" role="status">加载问数记录…</p>
          <p v-else-if="detail.error" class="err" role="alert">{{ detail.error }}</p>
          <template v-else-if="detail.view">
            <p class="hint">
              {{ RUN_STATUS_LABEL[detail.view.run.status] || detail.view.run.status }}
              · {{ detail.view.run.userName }} · {{ formatTime(detail.view.run.createdAt) }}
              <template v-if="detail.view.run.history.length"> · 带 {{ detail.view.run.history.length }} 条对话历史</template>
            </p>
            <p v-for="(turn, tidx) in detail.view.run.history" :key="tidx" class="history">
              <b>{{ turn.role === 'user' ? '问' : '答' }}</b> {{ turn.content }}
            </p>
            <p class="history"><b>问</b> {{ detail.view.run.question }}</p>
            <p v-if="detail.view.run.answer" class="history"><b>答</b> {{ detail.view.run.answer }}</p>
            <p v-if="detail.view.run.error" class="err">{{ detail.view.run.error }}</p>
            <pre v-if="detail.view.run.sql" class="sql">{{ detail.view.run.sql }}</pre>
          </template>
        </div>

        <p v-if="item.reply" class="reply">
          <span class="hint">{{ item.handledBy || '管理员' }} 答复：</span>{{ item.reply }}
        </p>

        <form v-if="handling.id === item.id" class="inline-form" @submit.prevent="saveHandle">
          <label v-if="handling.status === 'adopted'">
            采纳后落到哪里
            <select v-model="handling.disposition">
              <option value="">暂不指定</option>
              <option v-for="(label, key) in DISPOSITION_LABEL" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
          <label>
            答复（提出人能看到）
            <textarea v-model="handling.reply" rows="2" maxlength="1000" />
          </label>
          <div class="actions">
            <button type="button" :disabled="busy" @click="handling.id = 0">取消</button>
            <button class="primary" type="submit" :disabled="busy">
              {{ handling.status === 'adopted' ? '确认采纳' : handling.status === 'rejected' ? '确认不采纳' : '重新打开' }}
            </button>
          </div>
        </form>

        <div v-else-if="editing.id !== item.id" class="ops">
          <template v-if="admin">
            <button v-if="item.status !== 'adopted'" class="tiny" type="button" @click="startHandle(item, 'adopted')">采纳</button>
            <button v-if="item.status !== 'rejected'" class="tiny" type="button" @click="startHandle(item, 'rejected')">不采纳</button>
            <button v-if="item.status === 'adopted'" class="tiny" type="button" @click="startHandle(item, 'adopted')">改去向或答复</button>
            <button v-if="item.status !== 'open'" class="tiny" type="button" @click="startHandle(item, 'open')">重新打开</button>
          </template>
          <button v-if="canEdit(item)" class="tiny" type="button" @click="startEdit(item)">修改</button>
          <button v-if="canDelete(item)" class="tiny danger" type="button" :disabled="busy" @click="removeItem(item)">删除</button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.suggestions {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px 24px;
}

.suggestions > * {
  max-width: 980px;
  margin-left: auto;
  margin-right: auto;
}

.new {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.new label {
  flex: 1;
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

.state.open { color: var(--warn); }
.state.adopted { color: var(--ok); }
.state.rejected { color: var(--muted); }

.content,
.reply,
.run-link,
.history {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.reply,
.run-link,
.history {
  font-size: 13px;
}

.run {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 12px;
  border-left: 2px solid var(--line);
}

.run p {
  margin: 0;
}

.run .sql {
  margin: 4px 0 0;
  font-size: 12px;
}

.inline-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
