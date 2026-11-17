<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { authSetup } from './api'
import { setAccessToken } from './auth'
import { errMessage } from './types'

const emit = defineEmits<{ 'signed-in': [] }>()

const form = reactive({ username: '', displayName: '', password: '', confirm: '' })
const busy = ref(false)
const error = ref('')
const usernameInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)
const confirmInput = ref<HTMLInputElement | null>(null)

const mismatch = computed(() => !!form.confirm && form.confirm !== form.password)

onMounted(() => {
  void nextTick(() => usernameInput.value?.focus())
})

/** 浏览器自动填充有时不触发 v-model，提交前从 DOM 再读一遍。 */
function syncFromDom() {
  if (usernameInput.value) {
    form.username = usernameInput.value.value.trim()
  }
  if (passwordInput.value) {
    form.password = passwordInput.value.value
  }
  if (confirmInput.value) {
    form.confirm = confirmInput.value.value
  }
}

async function submit() {
  syncFromDom()
  if (form.username.length < 3) {
    error.value = '用户名至少 3 位'
    return
  }
  if (form.password.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  if (form.confirm !== form.password) {
    error.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const session = await authSetup(form.username, form.password, form.displayName)
    setAccessToken(session.token)
    emit('signed-in')
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-gate">
    <section class="auth-gate__panel" aria-labelledby="setup-title">
      <header>
        <p class="auth-gate__brand">联邦</p>
        <h1 id="setup-title" class="auth-gate__title">创建管理员</h1>
        <p class="auth-gate__lead">
          首次使用需设置管理员。完成后即可登录，并在「用户」里管理其他人。
        </p>
      </header>

      <form class="auth-gate__form" novalidate @submit.prevent="submit">
        <label>
          用户名
          <input
            ref="usernameInput"
            v-model.trim="form.username"
            name="username"
            required
            minlength="3"
            maxlength="32"
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            placeholder="3–32 位小写字母、数字或 . _ -"
          />
        </label>
        <label>
          显示名（可选）
          <input v-model.trim="form.displayName" name="displayName" maxlength="64" autocomplete="name" />
        </label>
        <label>
          密码
          <input
            ref="passwordInput"
            v-model="form.password"
            name="password"
            type="password"
            required
            minlength="8"
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </label>
        <label>
          确认密码
          <input
            ref="confirmInput"
            v-model="form.confirm"
            name="confirm"
            type="password"
            required
            minlength="8"
            autocomplete="new-password"
            :aria-invalid="mismatch"
          />
        </label>
        <p v-if="mismatch" class="auth-gate__field-hint" role="status">两次输入的密码不一致</p>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <!-- 与登录页一致：仅 busy / 密码不一致时禁用，避免填完仍灰掉点不了（含自动填充） -->
        <button class="primary" type="submit" :disabled="busy || mismatch">
          {{ busy ? '创建中…' : '创建并进入' }}
        </button>
      </form>
    </section>
  </main>
</template>
