<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { authSetup } from './api'
import { setAccessToken } from './auth'
import { errMessage } from './types'

const emit = defineEmits<{ 'signed-in': [] }>()

const form = reactive({ username: '', displayName: '', password: '', confirm: '' })
const busy = ref(false)
const error = ref('')

const mismatch = computed(() => !!form.confirm && form.confirm !== form.password)

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
  <main class="login">
    <section class="card" aria-labelledby="setup-title">
      <header>
        <p class="brand">联邦</p>
        <h1 id="setup-title">首次配置管理员</h1>
        <p class="hint">控制台需要登录。请创建第一个管理员账号，之后可用它登录并管理其他用户。</p>
      </header>

      <form @submit.prevent="submit">
        <label>
          管理员用户名
          <input
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
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="primary" type="submit" :disabled="busy">
          {{ busy ? '创建中…' : '创建管理员并进入' }}
        </button>
      </form>
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
  width: min(400px, 100%);
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
  line-height: 1.5;
  color: var(--muted);
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

.err {
  margin: 0;
  font-size: 13px;
}
</style>
