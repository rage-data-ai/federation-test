<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { authLogin, authRegister } from './api'
import { beginOidcLogin, readOidcConfig, setAccessToken } from './auth'
import { errMessage } from './types'

defineProps<{ notice?: string }>()
const emit = defineEmits<{ 'signed-in': [] }>()

type Mode = 'login' | 'register'

const mode = ref<Mode>('login')
const form = reactive({ username: '', displayName: '', password: '', confirm: '', token: '' })
const busy = ref(false)
const error = ref('')
const oidcEnabled = !!readOidcConfig()

const mismatch = computed(
  () => mode.value === 'register' && !!form.confirm && form.confirm !== form.password
)

function switchMode(next: Mode) {
  mode.value = next
  error.value = ''
  form.password = ''
  form.confirm = ''
}

async function submit() {
  if (mismatch.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const session =
      mode.value === 'login'
        ? await authLogin(form.username, form.password)
        : await authRegister(form.username, form.password, form.displayName)
    setAccessToken(session.token)
    emit('signed-in')
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}

function useToken() {
  if (!form.token.trim()) {
    return
  }
  setAccessToken(form.token)
  form.token = ''
  emit('signed-in')
}

async function loginOidc() {
  error.value = ''
  try {
    await beginOidcLogin()
  } catch (e) {
    error.value = errMessage(e)
  }
}
</script>

<template>
  <main class="login">
    <section class="card" aria-labelledby="login-title">
      <header>
        <p class="brand">联邦</p>
        <h1 id="login-title">{{ mode === 'login' ? '登录' : '注册账号' }}</h1>
        <p class="hint">跨源联邦查询控制台</p>
      </header>

      <div class="tabs" role="tablist" aria-label="登录或注册">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'login'"
          :class="{ on: mode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'register'"
          :class="{ on: mode === 'register' }"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <p v-if="notice && !error" class="banner warn" role="status">{{ notice }}</p>

      <form @submit.prevent="submit">
        <label>
          用户名
          <input
            v-model.trim="form.username"
            name="username"
            required
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            :placeholder="mode === 'register' ? '3–32 位小写字母、数字或 . _ -' : ''"
          />
        </label>
        <label v-if="mode === 'register'">
          显示名（可选）
          <input v-model.trim="form.displayName" name="displayName" maxlength="64" autocomplete="name" />
        </label>
        <label>
          密码
          <input
            v-model="form.password"
            name="password"
            type="password"
            required
            :minlength="mode === 'register' ? 8 : undefined"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :placeholder="mode === 'register' ? '至少 8 位' : ''"
          />
        </label>
        <label v-if="mode === 'register'">
          确认密码
          <input
            v-model="form.confirm"
            name="confirm"
            type="password"
            required
            autocomplete="new-password"
            :aria-invalid="mismatch"
          />
        </label>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="primary" type="submit" :disabled="busy">
          {{ busy ? '请稍候…' : mode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>

      <p class="hint switch">
        <template v-if="mode === 'login'">
          还没有账号？<button class="link" type="button" @click="switchMode('register')">注册一个</button>
        </template>
        <template v-else>
          已有账号？<button class="link" type="button" @click="switchMode('login')">直接登录</button>
        </template>
      </p>

      <details class="other">
        <summary>其他登录方式</summary>
        <button v-if="oidcEnabled" type="button" @click="loginOidc">通过 OIDC 单点登录</button>
        <form class="token" @submit.prevent="useToken">
          <label>
            访问令牌
            <input
              v-model="form.token"
              name="token"
              type="password"
              autocomplete="off"
              placeholder="粘贴网关 token 或 IdP access_token"
            />
          </label>
          <button type="submit" :disabled="!form.token.trim()">使用令牌</button>
        </form>
      </details>
    </section>
  </main>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: min(380px, 100%);
  padding: 28px 28px 22px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.brand {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--accent-2);
}

h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

header .hint {
  margin: 0;
  font-size: 12px;
}

.tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  border-radius: 10px;
  background: var(--panel-2);
}

.tabs button {
  border: 0;
  background: transparent;
  color: var(--muted);
}

.tabs button.on {
  background: var(--panel);
  color: var(--text);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
}

form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

form .primary {
  margin-top: 4px;
  padding: 10px;
}

.err,
.banner {
  margin: 0;
  font-size: 13px;
}

.banner.warn {
  color: var(--warn);
  background: rgba(210, 153, 34, 0.12);
}

.switch {
  margin: 0;
  font-size: 12px;
  text-align: center;
}

.other {
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  color: var(--muted);
}

.other summary {
  cursor: pointer;
}

.other > button,
.other .token {
  margin-top: 12px;
}

.other > button {
  width: 100%;
}

.token {
  flex-direction: row;
  align-items: flex-end;
  gap: 8px;
}

.token label {
  flex: 1;
}
</style>
