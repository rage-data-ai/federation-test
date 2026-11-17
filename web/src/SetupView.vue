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

const mismatch = computed(() => !!form.confirm && form.confirm !== form.password)
const canSubmit = computed(
  () =>
    form.username.trim().length >= 3 &&
    form.password.length >= 8 &&
    form.confirm === form.password &&
    !busy.value
)

onMounted(() => {
  void nextTick(() => usernameInput.value?.focus())
})

async function submit() {
  if (mismatch.value) {
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
        <button class="primary" type="submit" :disabled="!canSubmit">
          {{ busy ? '创建中…' : '创建并进入' }}
        </button>
      </form>
    </section>
  </main>
</template>
