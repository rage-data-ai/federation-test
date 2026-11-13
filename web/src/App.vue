<script setup>
import { computed, onMounted, onUnmounted, provide, reactive, ref } from 'vue'
import { authMe, llmConfig, llmStatus, saveLlm, testLlm } from './api'
import {
  beginOidcLogin,
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

const MODULES = [
  { key: 'datasource', label: '数据源管理', short: '数据源', icon: '⛁', component: DatasourceView },
  { key: 'ontology', label: '本体建模', short: '本体', icon: '◈', component: OntologyView },
  { key: 'ask', label: '智能问数', short: '问数', icon: '✦', component: AskView }
]

const saved = localStorage.getItem('module')
const active = ref(MODULES.some((item) => item.key === saved) ? saved : 'datasource')
const current = computed(() => MODULES.find((item) => item.key === active.value).component)

const showLlm = ref(false)
const llm = ref(null)
const llmRevision = ref(0)
const llmForm = reactive({ protocol: 'openai', baseUrl: '', apiKey: '', model: '' })
const llmMessage = ref('')
const llmError = ref('')
const llmBusy = ref('')
provide('openLlmSettings', openLlm)
provide('llmRevision', llmRevision)

const showAuth = ref(false)
const authForm = reactive({ token: getAccessToken() })
const authUser = ref(null)
const authError = ref('')
const authBusy = ref(false)
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
    authError.value = e.message
    showAuth.value = true
  }
  authForm.token = getAccessToken()
  await Promise.all([refreshLlm(), refreshAuth()])
})

onUnmounted(() => {
  window.clearInterval(themeTimer)
})

async function refreshLlm() {
  try {
    llm.value = await llmStatus()
  } catch {
    llm.value = null
  }
}

async function refreshAuth() {
  try {
    authUser.value = await authMe()
    authError.value = ''
  } catch (e) {
    authUser.value = null
    if (getAccessToken()) {
      authError.value = e.message
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
    authError.value = e.message
  } finally {
    authBusy.value = false
  }
}

function logoutAuth() {
  clearAccessToken()
  authForm.token = ''
  authUser.value = null
  showAuth.value = false
}

async function loginOidc() {
  authError.value = ''
  try {
    await beginOidcLogin()
  } catch (e) {
    authError.value = e.message
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
    authError.value = e.message
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
    llmForm.protocol = config.protocol || 'openai'
    llmForm.baseUrl = config.baseUrl || ''
    llmForm.model = config.model || ''
    llmForm.apiKey = ''
    llm.value = config
  } catch (e) {
    llmError.value = e.message
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
    llmError.value = e.message
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
    llmError.value = e.message
  } finally {
    llmBusy.value = ''
  }
}

function open(key) {
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
    <nav class="rail" aria-label="模块">
      <div class="brand" title="跨源联邦查询">联邦</div>
      <button
        v-for="item in MODULES"
        :key="item.key"
        class="module"
        :class="{ active: active === item.key }"
        type="button"
        :title="item.label"
        :aria-current="active === item.key ? 'page' : undefined"
        @click="open(item.key)"
      >
        <span class="glyph" aria-hidden="true">{{ item.icon }}</span>
        <span class="name">{{ item.short }}</span>
      </button>
      <div class="rail-foot">
        <button class="module" type="button" :title="authLabel" @click="openAuth">
          <span class="glyph" aria-hidden="true">⚷</span>
          <span class="name">身份</span>
        </button>
        <button class="module" :class="{ pending: llm && !llm.configured }" type="button" title="大模型配置" @click="openLlm">
          <span class="glyph" aria-hidden="true">✳</span>
          <span class="name">模型</span>
        </button>
        <button class="module" type="button" :title="themeLabel" @click="cycleTheme">
          <span class="glyph" aria-hidden="true">{{ themeIcon }}</span>
        </button>
      </div>
    </nav>

    <div class="stage">
      <KeepAlive>
        <component :is="current" />
      </KeepAlive>
    </div>

    <div v-if="showAuth" class="mask" @click.self="showAuth = false">
      <form class="dialog" @submit.prevent="saveAuthToken">
        <h3>访问身份</h3>
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
          <button type="button" @click="logoutAuth">清除</button>
          <button type="button" @click="showAuth = false">关闭</button>
          <button class="primary" type="submit" :disabled="authBusy">{{ authBusy ? '校验中…' : '保存并校验' }}</button>
        </div>
      </form>
    </div>

    <div v-if="showLlm" class="mask" @click.self="showLlm = false">
      <form class="dialog" @submit.prevent="saveLlmConfig">
        <h3>大模型</h3>
        <label>
          协议
          <select v-model="llmForm.protocol">
            <option value="openai">OpenAI 兼容</option>
            <option value="anthropic">Anthropic 兼容</option>
          </select>
        </label>
        <label>
          接口地址
          <input v-model="llmForm.baseUrl" required placeholder="例如 https://api.openai.com/v1" />
        </label>
        <label>
          模型名
          <input v-model="llmForm.model" required placeholder="例如 gpt-4o-mini" />
        </label>
        <label>
          API Key
          <input v-model="llmForm.apiKey" type="password" :placeholder="llm?.apiKeySet ? '已保存，留空则不修改' : '本地模型可留空'" />
        </label>
        <p class="hint">保存在 MySQL federal.fed_llm，立即生效，不用重启。密钥不会回传到页面。</p>
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
