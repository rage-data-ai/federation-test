<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
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
const usernameInput = ref<HTMLInputElement | null>(null)

const mismatch = computed(
  () => mode.value === 'register' && !!form.confirm && form.confirm !== form.password
)

onMounted(() => {
  void nextTick(() => usernameInput.value?.focus())
})

function switchMode(next: Mode) {
  mode.value = next
  error.value = ''
  form.password = ''
  form.confirm = ''
  void nextTick(() => usernameInput.value?.focus())
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
  <main class="auth-gate">
    <section class="auth-gate__panel" aria-labelledby="login-title">
      <header>
        <p class="auth-gate__brand">联邦</p>
        <h1 id="login-title" class="auth-gate__title">
          {{ mode === 'login' ? '登录控制台' : '注册账号' }}
        </h1>
        <p class="auth-gate__lead">
          {{ mode === 'login' ? '跨源联邦查询 · 本地账号或令牌进入' : '注册后默认可查询；管理能力由管理员授予' }}
        </p>
      </header>

      <div class="auth-gate__tabs" role="tablist" aria-label="登录或注册">
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

      <p v-if="notice && !error" class="auth-gate__banner" role="status">{{ notice }}</p>

      <form class="auth-gate__form" @submit.prevent="submit">
        <label>
          用户名
          <input
            ref="usernameInput"
            v-model.trim="form.username"
            name="username"
            required
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            :placeholder="mode === 'register' ? '3–32 位小写字母、数字或 . _ -' : '用户名'"
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
            :placeholder="mode === 'register' ? '至少 8 位' : '密码'"
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
        <p v-if="mismatch" class="auth-gate__field-hint" role="status">两次输入的密码不一致</p>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="primary" type="submit" :disabled="busy || mismatch">
          {{ busy ? '请稍候…' : mode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>

      <details class="auth-gate__other">
        <summary>其他方式</summary>
        <button v-if="oidcEnabled" type="button" @click="loginOidc">OIDC 单点登录</button>
        <form class="auth-gate__token" @submit.prevent="useToken">
          <label>
            访问令牌
            <input
              v-model="form.token"
              name="token"
              type="password"
              autocomplete="off"
              placeholder="网关 token 或 IdP access_token"
            />
          </label>
          <button type="submit" :disabled="!form.token.trim()">使用</button>
        </form>
      </details>
    </section>
  </main>
</template>
