<script setup lang="ts">
import { inject, onMounted, reactive, ref, type Ref } from 'vue'
import { createUser, listUsers, resetUserPassword, updateUser } from './api'
import { useFocusTrap } from './composables/useFocusTrap'
import type { AuthMe, UserAccount, UserPatch } from './types'
import { errMessage } from './types'

const authUser = inject<Ref<AuthMe | null>>('authUser', ref(null))

const users = ref<UserAccount[]>([])
const loading = ref(false)
const busy = ref(0)
const error = ref('')
const message = ref('')
const createForm = reactive({ username: '', displayName: '', password: '' })

const reset = reactive({ id: 0, username: '', password: '', error: '' })
const resetOpen = ref(false)
const resetDialog = ref<HTMLElement | null>(null)
useFocusTrap(resetOpen, resetDialog, closeReset)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    users.value = await listUsers()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    loading.value = false
  }
}

function isSelf(user: UserAccount) {
  return user.username === authUser.value?.name
}

function isAdmin(user: UserAccount) {
  return user.roles.includes('ADMIN')
}

function formatTime(ms: number) {
  return ms > 0 ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function patch(user: UserAccount, change: UserPatch, done: string) {
  busy.value = user.id
  error.value = ''
  message.value = ''
  try {
    const saved = await updateUser(user.id, change)
    users.value = users.value.map((item) => (item.id === saved.id ? saved : item))
    message.value = done
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = 0
  }
}

function toggleAdmin(user: UserAccount) {
  return isAdmin(user)
    ? patch(user, { roles: ['QUERY'] }, `已取消 ${user.username} 的管理员角色`)
    : patch(user, { roles: ['ADMIN'] }, `已把 ${user.username} 设为管理员`)
}

function toggleStatus(user: UserAccount) {
  return user.status === 'active'
    ? patch(user, { status: 'disabled' }, `已禁用 ${user.username}，其登录会话已失效`)
    : patch(user, { status: 'active' }, `已启用 ${user.username}`)
}

function openReset(user: UserAccount) {
  reset.id = user.id
  reset.username = user.username
  reset.password = ''
  reset.error = ''
  resetOpen.value = true
}

function closeReset() {
  resetOpen.value = false
}

async function submitReset() {
  busy.value = reset.id
  reset.error = ''
  try {
    await resetUserPassword(reset.id, reset.password)
    message.value = `已重置 ${reset.username} 的密码，其登录会话已失效`
    closeReset()
  } catch (e) {
    reset.error = errMessage(e)
  } finally {
    busy.value = 0
  }
}

async function submitCreate() {
  busy.value = -1
  error.value = ''
  message.value = ''
  try {
    const created = await createUser(createForm.username, createForm.password, createForm.displayName)
    users.value = [...users.value, created].sort((a, b) => a.username.localeCompare(b.username))
    message.value = `已创建账号 ${created.username}`
    createForm.username = ''
    createForm.displayName = ''
    createForm.password = ''
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = 0
  }
}
</script>

<template>
  <section class="users" aria-labelledby="users-title">
    <header class="module-head">
      <h1 id="users-title">用户管理</h1>
      <div class="head-side">
        <span class="head-note" role="status">{{ loading ? '加载中…' : `${users.length} 个账号` }}</span>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div class="body">
      <p class="hint">
        自助注册默认关闭。在此创建查询账号，或授予/收回管理员、禁用、重置密码。
      </p>
      <p v-if="error" class="err banner" role="alert">{{ error }}</p>
      <p v-else-if="message" class="ok" role="status">{{ message }}</p>

      <form class="create-user" @submit.prevent="submitCreate">
        <label>
          用户名
          <input v-model.trim="createForm.username" required autocomplete="off" placeholder="3–32 位" />
        </label>
        <label>
          显示名
          <input v-model.trim="createForm.displayName" autocomplete="off" placeholder="可选" />
        </label>
        <label>
          初始密码
          <input v-model="createForm.password" type="password" required minlength="8" autocomplete="new-password" />
        </label>
        <button type="submit" :disabled="busy === -1">创建账号</button>
      </form>

      <table v-if="users.length">
        <thead>
          <tr>
            <th scope="col">用户名</th>
            <th scope="col">显示名</th>
            <th scope="col">角色</th>
            <th scope="col">状态</th>
            <th scope="col">注册时间</th>
            <th scope="col">最近登录</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id" :class="{ off: user.status !== 'active' }">
            <th scope="row">
              {{ user.username }}
              <span v-if="isSelf(user)" class="hint">（我）</span>
            </th>
            <td>{{ user.displayName }}</td>
            <td>
              <span class="tag" :class="{ admin: isAdmin(user) }">{{ isAdmin(user) ? '管理员' : '查询' }}</span>
            </td>
            <td>
              <span class="tag" :class="user.status">{{ user.status === 'active' ? '正常' : '已禁用' }}</span>
            </td>
            <td>{{ formatTime(user.createdAt) }}</td>
            <td>{{ formatTime(user.lastLoginAt) }}</td>
            <td>
              <div class="ops">
              <button
                class="tiny"
                type="button"
                :disabled="busy === user.id || isSelf(user)"
                :title="isSelf(user) ? '不能改自己的角色' : undefined"
                @click="toggleAdmin(user)"
              >
                {{ isAdmin(user) ? '取消管理员' : '设为管理员' }}
              </button>
              <button
                class="tiny"
                type="button"
                :class="{ danger: user.status === 'active' }"
                :disabled="busy === user.id || isSelf(user)"
                :title="isSelf(user) ? '不能禁用自己' : undefined"
                @click="toggleStatus(user)"
              >
                {{ user.status === 'active' ? '禁用' : '启用' }}
              </button>
              <button class="tiny" type="button" :disabled="busy === user.id" @click="openReset(user)">
                重置密码
              </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading && !error" class="empty">还没有本地账号</p>
    </div>

    <div v-if="resetOpen" class="mask" @click.self="closeReset">
      <form
        ref="resetDialog"
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reset-title"
        @submit.prevent="submitReset"
      >
        <h3 id="reset-title">重置密码</h3>
        <p class="hint">为 {{ reset.username }} 设置新密码。保存后该账号在所有设备上都需要重新登录。</p>
        <label>
          新密码
          <input
            v-model="reset.password"
            type="password"
            required
            minlength="8"
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </label>
        <p v-if="reset.error" class="err" role="alert">{{ reset.error }}</p>
        <div class="actions">
          <button type="button" @click="closeReset">取消</button>
          <button class="primary" type="submit" :disabled="busy === reset.id">保存</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.users {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px 24px;
}

.body > * {
  max-width: 1080px;
  margin-left: auto;
  margin-right: auto;
}

.body > .hint {
  margin-top: 0;
  font-size: 12px;
}

.create-user {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: end;
  margin: 12px auto 18px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.create-user label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
}

.create-user input {
  min-width: 140px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--bg);
  color: inherit;
}

table {
  width: 100%;
  min-width: 0;
  border-collapse: collapse;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  font-size: 13px;
}

th,
td {
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  font-weight: 400;
  white-space: nowrap;
  cursor: default;
}

thead th {
  background: var(--th);
  color: var(--muted);
  font-size: 12px;
}

tbody th {
  font-weight: 600;
}

tbody tr:last-child th,
tbody tr:last-child td {
  border-bottom: 0;
}

tr.off th,
tr.off td {
  color: var(--muted);
}

.tag {
  padding: 1px 8px;
  border: 1px solid currentColor;
  border-radius: 10px;
  font-size: 11px;
  color: var(--muted);
}

.tag.admin {
  color: var(--accent-2);
}

.tag.active {
  color: var(--ok);
}

.tag.disabled {
  color: var(--err);
}

.ops {
  display: flex;
  gap: 6px;
}
</style>
