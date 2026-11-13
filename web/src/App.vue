<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, reactive, ref, type Component } from 'vue'
import { authLogout, authMe, llmConfig, saveLlm, testLlm } from './api'
import { useFocusTrap } from './composables/useFocusTrap'
import {
  beginOidcLogin,
  beginOidcLogout,
  clearAccessToken,
  completeOidcCallback,
  getAccessToken,
  getRefreshToken,
  readOidcConfig,
  refreshAccessToken,
  setAccessToken
} from './auth'
import AskView from './AskView.vue'
import DatasourceView from './DatasourceView.vue'
import OntologyView from './OntologyView.vue'
import { AGENT_MODELS, DEFAULT_MODEL, findModel } from './models'
import type { AuthMe, LlmConfig, LlmStatus } from './types'
import { errMessage } from './types'

type ModuleDef = {
  key: string
  label: string
  short: string
  icon: string
  component: Component
}

const MODULES: ModuleDef[] = [
  { key: 'datasource', label: '数据源管理', short: '数据源', icon: '⛁', component: DatasourceView },
  { key: 'ontology', label: '本体建模', short: '本体', icon: '◈', component: OntologyView },
  { key: 'ask', label: '智能问数', short: '问数', icon: '✦', component: AskView }
]

const saved = localStorage.getItem('module')
const active = ref(MODULES.some((item) => item.key === saved) ? (saved as string) : 'datasource')
const current = computed(() => MODULES.find((item) => item.key === active.value)?.component ?? DatasourceView)

const showLlm = ref(false)
const llmDialog = ref<HTMLElement | null>(null)
const llm = ref<LlmStatus | LlmConfig | null>(null)
useFocusTrap(showLlm, llmDialog, () => {
  showLlm.value = false
})
const llmRevision = ref(0)
const llmForm = reactive({ protocol: 'openai', baseUrl: '', apiKey: '', model: '' })
const llmMessage = ref('')
const llmError = ref('')
const llmBusy = ref('')
provide('openLlmSettings', openLlm)
provide('llmRevision', llmRevision)

const showAuth = ref(false)
const authDialog = ref<HTMLElement | null>(null)
const authForm = reactive({ token: getAccessToken() })
const authUser = ref<AuthMe | null>(null)
const authError = ref('')
const authBusy = ref(false)
useFocusTrap(showAuth, authDialog, () => {
  showAuth.value = false
})
const oidcEnabled = !!readOidcConfig()
const authLabel = computed(() => {
  if (authUser.value?.name) {
    return `身份：${authUser.value.name}`
  }
  return getAccessToken() ? '身份：已配置令牌' : '身份：未登录'
})

const themeMode = ref(localStorage.getItem('theme-mode') || 'auto')
const theme = ref('dark')
let themeTimer = 0

const themeLabel = computed(() => {
  if (themeMode.value === 'auto') {
    return theme.value === 'dark' ? '主题：自动（夜间）' : '主题：自动（日间）'
  }
  return themeMode.value === 'dark' ? '主题：深色' : '主题：浅色'
})
const themeIcon = computed(() => (themeMode.value === 'auto' ? '◐' : themeMode.value === 'dark' ? '☾' : '☀'))

onMounted(async () => {
  applyTheme()
  themeTimer = window.setInterval(applyTheme, 60_000)
  try {
    await completeOidcCallback()
  } catch (e) {
    authError.value = errMessage(e)
    showAuth.value = true
  }
  authForm.token = getAccessToken()
  await Promise.all([refreshLlm(), refreshAuth()])
})

onUnmounted(() => {
  window.clearInterval(themeTimer)
})

const modelOptions = computed(() => {
  if (findModel(llmForm.model) || !llmForm.model) {
    return AGENT_MODELS
  }
  return [...AGENT_MODELS, { id: llmForm.model, label: llmForm.model, protocol: llmForm.protocol, baseUrl: llmForm.baseUrl }]
})

async function refreshLlm() {
  try {
    llm.value = await llmConfig()
  } catch {
    llm.value = null
  }
}

function applyModel(id: string) {
  const preset = findModel(id)
  if (!preset) {
    return
  }
  llmForm.model = preset.id
  llmForm.protocol = preset.protocol
  llmForm.baseUrl = preset.baseUrl
}

async function refreshAuth() {
  try {
    authUser.value = await authMe()
    authError.value = ''
  } catch (e) {
    authUser.value = null
    if (getAccessToken()) {
      authError.value = errMessage(e)
    }
  }
}

function openAuth() {
  authForm.token = getAccessToken()
  authError.value = ''
  showAuth.value = true
  refreshAuth()
}

async function saveAuthToken() {
  authBusy.value = true
  authError.value = ''
  try {
    setAccessToken(authForm.token)
    await refreshAuth()
    showAuth.value = false
  } catch (e) {
    authError.value = errMessage(e)
  } finally {
    authBusy.value = false
  }
}

async function logoutAuth() {
  try {
    await authLogout()
  } catch {
    // 网关可能未开启鉴权
  }
  clearAccessToken()
  authForm.token = ''
  authUser.value = null
  showAuth.value = false
}

async function logoutOidc() {
  try {
    await authLogout()
  } catch {
    // ignore
  }
  authUser.value = null
  authForm.token = ''
  showAuth.value = false
  beginOidcLogout()
}

async function loginOidc() {
  authError.value = ''
  try {
    await beginOidcLogin()
  } catch (e) {
    authError.value = errMessage(e)
  }
}

async function refreshOidc() {
  authBusy.value = true
  authError.value = ''
  try {
    await refreshAccessToken()
    authForm.token = getAccessToken()
    await refreshAuth()
  } catch (e) {
    authError.value = errMessage(e)
  } finally {
    authBusy.value = false
  }
}

async function openLlm() {
  llmMessage.value = ''
  llmError.value = ''
  showLlm.value = true
  try {
    const config = await llmConfig()
    llmForm.protocol = config.protocol || DEFAULT_MODEL.protocol
    llmForm.baseUrl = config.baseUrl || ''
    llmForm.model = config.model || ''
    llmForm.apiKey = ''
    llm.value = config
    if (!llmForm.model || !llmForm.baseUrl) {
      applyModel(llmForm.model || DEFAULT_MODEL.id)
    }
  } catch (e) {
    llmError.value = errMessage(e)
  }
}

function llmPayload() {
  return {
    protocol: llmForm.protocol,
    baseUrl: llmForm.baseUrl.trim(),
    apiKey: llmForm.apiKey,
    model: llmForm.model.trim()
  }
}

async function saveLlmConfig() {
  llmBusy.value = 'save'
  llmMessage.value = ''
  llmError.value = ''
  try {
    llm.value = await saveLlm(llmPayload())
    llmForm.apiKey = ''
    llmRevision.value += 1
    llmMessage.value = '已保存，立即生效'
  } catch (e) {
    llmError.value = errMessage(e)
  } finally {
    llmBusy.value = ''
  }
}

async function testLlmConfig() {
  llmBusy.value = 'test'
  llmMessage.value = ''
  llmError.value = ''
  try {
    const result = await testLlm(llmPayload())
    if (result.success) {
      llmMessage.value = `连接成功：${result.message || 'ok'}`
    } else {
      llmError.value = result.message || '连接失败'
    }
  } catch (e) {
    llmError.value = errMessage(e)
  } finally {
    llmBusy.value = ''
  }
}

function open(key: string) {
  active.value = key
  localStorage.setItem('module', key)
}

function clockTheme() {
  const hour = new Date().getHours()
  return hour >= 6 && hour < 18 ? 'light' : 'dark'
}

function applyTheme() {
  theme.value = themeMode.value === 'auto' ? clockTheme() : themeMode.value
  document.documentElement.dataset.theme = theme.value
}

function cycleTheme() {
  themeMode.value = themeMode.value === 'auto' ? 'light' : themeMode.value === 'light' ? 'dark' : 'auto'
  localStorage.setItem('theme-mode', themeMode.value)
  applyTheme()
}
</script>

<template>
  <div class="shell">
    <a class="skip-link" href="#main-stage">跳到主内容</a>
    <nav class="rail" aria-label="模块">
      <div class="brand" title="跨源联邦查询">联邦</div>
      <button
        v-for="item in MODULES"
        :key="item.key"
        class="module"
        :class="{ active: active === item.key }"
        type="button"
        :title="item.label"
        :aria-label="item.label"
        :aria-current="active === item.key ? 'page' : undefined"
        @click="open(item.key)"
      >
        <span class="glyph" aria-hidden="true">{{ item.icon }}</span>
        <span class="name">{{ item.short }}</span>
      </button>
      <div class="rail-foot">
        <button class="module" type="button" :title="authLabel" :aria-label="authLabel" @click="openAuth">
          <span class="glyph" aria-hidden="true">⚷</span>
          <span class="name">身份</span>
        </button>
        <button class="module" :class="{ pending: llm && (!llm.configured || !llm.apiKeySet) }" type="button" title="大模型配置" aria-label="大模型配置" @click="openLlm">
          <span class="glyph" aria-hidden="true">✳</span>
          <span class="name">模型</span>
        </button>
        <button class="module" type="button" :title="themeLabel" :aria-label="themeLabel" @click="cycleTheme">
          <span class="glyph" aria-hidden="true">{{ themeIcon }}</span>
        </button>
      </div>
    </nav>

    <main id="main-stage" class="stage" tabindex="-1">
      <KeepAlive>
        <component :is="current" />
      </KeepAlive>
    </main>

    <div v-if="showAuth" class="mask" @click.self="showAuth = false">
      <form
        ref="authDialog"
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="auth-title"
        @submit.prevent="saveAuthToken"
      >
        <h3 id="auth-title">访问身份</h3>
        <p v-if="authUser" class="hint">
          当前用户 {{ authUser.name }} · 角色 {{ (authUser.roles || []).join(', ') || '无' }}
          · 鉴权 {{ authUser.securityEnabled ? '已开启' : '关闭' }}
          · JWT {{ authUser.jwtEnabled ? '已启用' : '未启用' }}
          · Catalog ACL {{ authUser.catalogAcl ? (authUser.catalogs || []).join(', ') || '无' : '未启用' }}
        </p>
        <label>
          Bearer / Access Token
          <input v-model="authForm.token" type="password" autocomplete="off" placeholder="粘贴网关 token 或 IdP access_token" />
        </label>
        <p class="hint">令牌仅保存在本机 localStorage，请求时以 Authorization: Bearer 发送。生产请用 OIDC 登录。</p>
        <p v-if="authError" class="err">{{ authError }}</p>
        <div class="actions">
          <button v-if="oidcEnabled" type="button" @click="loginOidc">OIDC 登录</button>
          <button v-if="oidcEnabled && getRefreshToken()" type="button" :disabled="authBusy" @click="refreshOidc">刷新令牌</button>
          <button v-if="oidcEnabled" type="button" @click="logoutOidc">OIDC 注销</button>
          <button type="button" @click="logoutAuth">清除</button>
          <button type="button" @click="showAuth = false">关闭</button>
          <button class="primary" type="submit" :disabled="authBusy">{{ authBusy ? '校验中…' : '保存并校验' }}</button>
        </div>
      </form>
    </div>

    <div v-if="showLlm" class="mask" @click.self="showLlm = false">
      <form
        ref="llmDialog"
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="llm-title"
        @submit.prevent="saveLlmConfig"
      >
        <h3 id="llm-title">大模型</h3>
        <label>
          模型
          <select v-model="llmForm.model" required @change="applyModel(llmForm.model)">
            <option v-for="item in modelOptions" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </label>
        <label>
          协议
          <select v-model="llmForm.protocol">
            <option value="openai">OpenAI 兼容</option>
            <option value="anthropic">Anthropic 兼容</option>
          </select>
        </label>
        <label>
          接口地址
          <input v-model="llmForm.baseUrl" required placeholder="https://api.x.ai/v1" />
        </label>
        <label>
          API Key
          <input v-model="llmForm.apiKey" type="password" :placeholder="llm?.apiKeySet ? '已保存，留空则不修改' : '在 xAI 控制台创建后填写'" />
        </label>
        <p class="hint">可选 Grok 4.7、4.6、4.5，走 xAI 的 OpenAI 兼容接口。保存在 MySQL federal.fed_llm，立即生效。密钥不会回传到页面。</p>
        <p v-if="llmMessage" class="ok">{{ llmMessage }}</p>
        <p v-if="llmError" class="err">{{ llmError }}</p>
        <div class="actions">
          <button type="button" :disabled="!!llmBusy" @click="testLlmConfig">{{ llmBusy === 'test' ? '测试中…' : '测试连接' }}</button>
          <button type="button" @click="showLlm = false">关闭</button>
          <button class="primary" type="submit" :disabled="!!llmBusy">{{ llmBusy === 'save' ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 68px 1fr;
  height: 100%;
}

.rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 0;
  border-right: 1px solid var(--line);
  background: var(--panel);
}

.brand {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--accent-2);
}

.module {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  width: 56px;
  padding: 8px 0 6px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
}

.module:hover {
  color: var(--text);
  background: var(--panel-2);
}

.module.active {
  color: var(--text);
  background: var(--panel-2);
  box-shadow: inset 3px 0 0 var(--accent);
}

.glyph {
  font-size: 18px;
  line-height: 1;
}

.name {
  font-size: 11px;
}

.rail-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  margin-top: auto;
}

.module.pending .name {
  color: var(--warn);
}

.stage {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.stage > * {
  flex: 1;
  min-height: 0;
}
</style>
