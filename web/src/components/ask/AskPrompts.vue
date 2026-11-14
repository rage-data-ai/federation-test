<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch, type Ref } from 'vue'
import {
  activateAskPrompt,
  createAskPrompt,
  deleteAskPrompt,
  listAskPrompts,
  proposeAskPromptsFromFeedback,
  updateAskPrompt
} from '../../api'
import { formatTime } from '../../composables/useAskSuggestions'
import type { AskPrompt, AskPromptScope, AskPromptStatus, AuthMe } from '../../types'
import { errMessage } from '../../types'

const SCOPE_LABEL: Record<AskPromptScope, string> = {
  global: '全局',
  personal: '个人'
}

const STATUS_LABEL: Record<AskPromptStatus, string> = {
  draft: '草稿',
  active: '生效中',
  archived: '已归档'
}

const SOURCE_LABEL: Record<string, string> = {
  human: '人工',
  model: '模型'
}

const authUser = inject<Ref<AuthMe | null>>('authUser', ref(null))
const admin = computed(() => !!authUser.value?.roles?.includes('ADMIN'))
const me = computed(() => authUser.value?.name ?? '')

const items = ref<AskPrompt[]>([])
const scope = ref<AskPromptScope | ''>('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
let latest = 0

const draft = reactive<{ scope: AskPromptScope; content: string }>({
  scope: 'personal',
  content: ''
})
const editing = reactive({ id: 0, content: '' })

onMounted(load)
watch(scope, load)
watch(authUser, load)

async function load() {
  const ticket = ++latest
  loading.value = true
  try {
    const list = await listAskPrompts(scope.value)
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
  const content = draft.content.trim()
  if (!content || busy.value) {
    return
  }
  if (draft.scope === 'global' && !admin.value) {
    error.value = '只有管理员能新建全局提示词'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await createAskPrompt({ scope: draft.scope, content, source: 'human' })
    draft.content = ''
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

function canEdit(item: AskPrompt): boolean {
  if (item.scope === 'global') {
    return admin.value
  }
  return item.owner === me.value || item.createdBy === me.value
}

function canActivate(item: AskPrompt): boolean {
  if (item.status === 'active') {
    return false
  }
  if (item.scope === 'global') {
    return admin.value
  }
  return item.owner === me.value || item.createdBy === me.value
}

function canDelete(item: AskPrompt): boolean {
  return canEdit(item)
}

function startEdit(item: AskPrompt) {
  editing.id = item.id
  editing.content = item.content
}

async function saveEdit() {
  if (!editing.id || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await updateAskPrompt(editing.id, { content: editing.content })
    editing.id = 0
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

async function activate(item: AskPrompt) {
  busy.value = true
  error.value = ''
  try {
    await activateAskPrompt(item.id)
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

async function removeItem(item: AskPrompt) {
  if (!confirm(`删除这条${SCOPE_LABEL[item.scope]}提示词？`)) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await deleteAskPrompt(item.id)
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

async function proposeFromFeedback() {
  if (!admin.value || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await proposeAskPromptsFromFeedback()
    scope.value = 'global'
    await load()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="prompts">
    <form class="new" aria-label="新增提示词" @submit.prevent="submitDraft">
      <label class="inline">
        范围
        <select v-model="draft.scope">
          <option value="personal">个人</option>
          <option v-if="admin" value="global">全局</option>
        </select>
      </label>
      <label>
        补充提示词
        <textarea
          v-model="draft.content"
          rows="3"
          maxlength="8000"
          placeholder="接在问数基础规则之后的补充说明，例如口径偏好、禁用表、回答风格"
        />
      </label>
      <button class="primary" type="submit" :disabled="busy || !draft.content.trim()">提交提示词</button>
    </form>

    <div class="filters">
      <label class="inline">
        范围
        <select v-model="scope">
          <option value="">全部</option>
          <option v-for="(label, key) in SCOPE_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <button type="button" :disabled="loading" @click="load">刷新</button>
      <button
        v-if="admin"
        type="button"
        :disabled="busy || loading"
        @click="proposeFromFeedback"
      >
        从反馈生成
      </button>
      <span class="hint" role="status">{{ loading ? '加载中…' : `${items.length} 条` }}</span>
    </div>

    <p v-if="error" class="err banner" role="alert">{{ error }}</p>

    <p v-if="!loading && !items.length && !error" class="empty">
      {{ scope ? '没有符合条件的提示词' : '还没有提示词' }}
    </p>

    <ul class="list" aria-label="提示词列表">
      <li v-for="item in items" :key="item.id" class="item">
        <div class="meta">
          <span class="state" :class="item.status">{{ STATUS_LABEL[item.status] }}</span>
          <span class="hint">{{ SCOPE_LABEL[item.scope] }}</span>
          <span class="hint">v{{ item.version }}</span>
          <span class="hint">来源 {{ SOURCE_LABEL[item.source] || item.source }}</span>
          <span class="hint">
            {{ item.owner || item.createdBy }} · {{ formatTime(item.createdAt) }}
          </span>
          <span v-if="item.activatedBy" class="hint">由 {{ item.activatedBy }} 激活</span>
        </div>

        <form v-if="editing.id === item.id" class="inline-form" @submit.prevent="saveEdit">
          <label>
            修改内容
            <textarea v-model="editing.content" rows="4" maxlength="8000" />
          </label>
          <div class="actions">
            <button type="button" :disabled="busy" @click="editing.id = 0">取消</button>
            <button class="primary" type="submit" :disabled="busy || !editing.content.trim()">保存</button>
          </div>
        </form>
        <p v-else class="content">{{ item.content }}</p>

        <div v-if="editing.id !== item.id" class="ops">
          <button v-if="canEdit(item)" class="tiny" type="button" @click="startEdit(item)">修改</button>
          <button
            v-if="canActivate(item)"
            class="tiny"
            type="button"
            :disabled="busy"
            @click="activate(item)"
          >
            激活
          </button>
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
.prompts {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px 24px;
}

.prompts > * {
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

.state.draft { color: var(--warn); }
.state.active { color: var(--ok); }
.state.archived { color: var(--muted); }

.content {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
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
