<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, reactive, ref, type Component } from 'vue'
// askAppId：从「应用广场 / 问数应用」打开对话时带上已发布应用 id
import {
  ApiError,
  authChangePassword,
  authLogout,
  authMe,
  authSetupStatus,
  embedConfig,
  llmConfig,
  onUnauthorized,
  saveEmbed,
  saveLlm,
  testEmbed,
  testLlm
} from './api'
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
import AppsView from './AppsView.vue'
import AskView from './AskView.vue'
import DatasourceView from './DatasourceView.vue'
import KnowledgeView from './KnowledgeView.vue'
import LoginView from './LoginView.vue'
import OntologyView from './OntologyView.vue'
import PublishedAppsView from './PublishedAppsView.vue'
import SetupView from './SetupView.vue'
import UsersView from './UsersView.vue'
import { AGENT_MODELS, findModel } from './models'
import type { AuthMe, EmbedConfig, LlmConfig, LlmStatus } from './types'
import { errMessage } from './types'

type ModuleDef = {
  key: string
  label: string
  short: string
  icon: string
  component: Component
  /** 只在开启鉴权且当前用户是管理员时出现。 */
  adminOnly?: boolean
}

const MODULES: ModuleDef[] = [
  { key: 'datasource', label: '数据源管理', short: '数据源', icon: '⛁', component: DatasourceView },
  { key: 'ontology', label: '本体建模', short: '本体', icon: '◈', component: OntologyView },
  { key: 'knowledge', label: '知识库', short: '知识', icon: '▣', component: KnowledgeView },
  { key: 'ask', label: '智能问数', short: '问数', icon: '✦', component: AskView },
  { key: 'plaza', label: '应用广场', short: '广场', icon: '▦', component: PublishedAppsView },
  { key: 'apps', label: '问数应用', short: '编排', icon: '◇', component: AppsView },
  { key: 'users', label: '用户管理', short: '用户', icon: '☺', component: UsersView, adminOnly: true }
]

const saved = localStorage.getItem('module')
const active = ref(MODULES.some((item) => item.key === saved) ? (saved as string) : 'datasource')
const askAppId = ref('')
function openAskApp(appId: string) {
  askAppId.value = appId
  active.value = 'ask'
  localStorage.setItem('module', 'ask')
}

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
const embed = ref<EmbedConfig | null>(null)
const embedForm = reactive({ endpoint: '', apiKey: '', model: 'embedding-3' })
const embedMessage = ref('')
const embedError = ref('')
const embedBusy = ref('')

const EMBED_PRESETS = [
  {
    id: 'zhipu-embedding-3',
    label: '智谱 embedding-3',
    endpoint: 'https://open.bigmodel.cn/api/paas/v4/embeddings',
    model: 'embedding-3'
  },
  {
    id: 'openai-small',
    label: 'OpenAI text-embedding-3-small',
    endpoint: 'https://api.openai.com/v1/embeddings',
    model: 'text-embedding-3-small'
  }
] as const
provide('openLlmSettings', openLlm)
provide('llmRevision', llmRevision)

const showAuth = ref(false)
const authDialog = ref<HTMLElement | null>(null)
const authForm = reactive({ token: getAccessToken() })
const authUser = ref<AuthMe | null>(null)
provide('authUser', authUser)
const authError = ref('')
const authMessage = ref('')
const authBusy = ref(false)
const passwordForm = reactive({ oldPassword: '', newPassword: '' })
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
/** 已登录的具名用户在侧栏直接显示名字；匿名或未连上网关时仍显示“身份”。 */
const authShort = computed(() => {
  const user = authUser.value
  return user?.displayName || user?.name || '身份'
})

/** checking → setup（首次管理员）/ login / ready。鉴权始终开启。 */
const gate = ref<'checking' | 'setup' | 'login' | 'ready'>('checking')
const loginNotice = ref('')
let recovering = false

const modules = computed(() =>
  MODULES.filter((item) => !item.adminOnly || !!authUser.value?.roles?.includes('ADMIN'))
)
const activeKey = computed(() =>
  modules.value.some((item) => item.key === active.value) ? active.value : 'datasource'
)
const current = computed(
  () => modules.value.find((item) => item.key === activeKey.value)?.component ?? DatasourceView
)

onUnauthorized(() => {
  void handleUnauthorized()
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
  await Promise.all([refreshLlm(), bootstrapGate()])
})

onUnmounted(() => {
  window.clearInterval(themeTimer)
  onUnauthorized(null)
})

async function refreshLlm() {
  try {
    llm.value = await llmConfig()
  } catch {
    llm.value = null
  }
}

/** 快捷填入预设；手改模型名不会被改回去。 */
function applyModel(id: string) {
  const preset = findModel(id)
  if (!preset) {
    return
  }
  llmForm.model = preset.id
  llmForm.protocol = preset.protocol
  llmForm.baseUrl = preset.baseUrl
}

/** 先看是否需要首次管理员，再走登录态。鉴权始终开启，失败时进登录页而不是匿名控制台。 */
async function bootstrapGate() {
  try {
    const status = await authSetupStatus()
    if (status.needsSetup) {
      clearAccessToken()
      authUser.value = null
      gate.value = 'setup'
      return
    }
  } catch (e) {
    // setup-status 404/500（旧网关）或网络错误：继续用 /me 探测，绝不匿名放行。
    if (!(e instanceof ApiError)) {
      loginNotice.value = errMessage(e)
    }
  }
  await refreshAuth()
}

async function refreshAuth() {
  try {
    const me = await authMe()
    // 旧网关在关闭鉴权时会返回 anonymous；新逻辑一律要求具名登录。
    if (!me?.name || me.name === 'anonymous' || me.securityEnabled === false) {
      clearAccessToken()
      authUser.value = null
      gate.value = 'login'
      return
    }
    authUser.value = me
    authError.value = ''
    gate.value = 'ready'
  } catch (e) {
    authUser.value = null
    if (e instanceof ApiError && e.status === 401) {
      gate.value = 'login'
      return
    }
    clearAccessToken()
    gate.value = 'login'
    loginNotice.value = errMessage(e)
  }
}

/** 任一接口返回 401：先试 OIDC 刷新令牌，不行就回登录页。 */
async function handleUnauthorized() {
  if (recovering) {
    return
  }
  recovering = true
  try {
    if (oidcEnabled && getRefreshToken()) {
      try {
        await refreshAccessToken()
        await refreshAuth()
        if (gate.value === 'ready') {
          return
        }
      } catch {
        // 刷新不了就走下面的重新登录
      }
    }
    if (gate.value !== 'login') {
      loginNotice.value = getAccessToken() ? '令牌无效或登录已过期，请重新登录' : ''
    }
    clearAccessToken()
    authForm.token = ''
    authUser.value = null
    showAuth.value = false
    gate.value = 'login'
  } finally {
    recovering = false
  }
}

async function onSignedIn() {
  loginNotice.value = ''
  gate.value = 'checking'
  await Promise.all([refreshLlm(), refreshAuth()])
}

function openAuth() {
  authForm.token = getAccessToken()
  authError.value = ''
  authMessage.value = ''
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  showAuth.value = true
  refreshAuth()
}

async function changePassword() {
  authBusy.value = true
  authError.value = ''
  authMessage.value = ''
  try {
    await authChangePassword(passwordForm.oldPassword, passwordForm.newPassword)
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    authMessage.value = '密码已修改，其他设备上的登录已失效'
  } catch (e) {
    authError.value = errMessage(e)
  } finally {
    authBusy.value = false
  }
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
  loginNotice.value = ''
  await refreshAuth()
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
  embedMessage.value = ''
  embedError.value = ''
  showLlm.value = true
  try {
    const config = await llmConfig()
    llmForm.protocol = config.protocol || 'openai'
    llmForm.baseUrl = config.baseUrl || ''
    llmForm.model = config.model || ''
    llmForm.apiKey = ''
    llm.value = config
  } catch (e) {
    llmError.value = errMessage(e)
  }
  try {
    const config = await embedConfig()
    embedForm.endpoint = config.endpoint || ''
    embedForm.model = config.model || 'embedding-3'
    embedForm.apiKey = ''
    embed.value = config
  } catch (e) {
    embedError.value = errMessage(e)
  }
}

function applyEmbedPreset(id: string) {
  const preset = EMBED_PRESETS.find((item) => item.id === id)
  if (!preset) {
    return
  }
  embedForm.endpoint = preset.endpoint
  embedForm.model = preset.model
}

function embedPayload() {
  return {
    endpoint: embedForm.endpoint.trim(),
    apiKey: embedForm.apiKey,
    model: embedForm.model.trim()
  }
}

async function saveEmbedConfig() {
  embedBusy.value = 'save'
  embedMessage.value = ''
  embedError.value = ''
  try {
    embed.value = await saveEmbed(embedPayload())
    embedForm.apiKey = ''
    embedMessage.value = embed.value.dim
      ? `已保存，立即生效（dim=${embed.value.dim}）`
      : '已保存，立即生效'
  } catch (e) {
    embedError.value = errMessage(e)
  } finally {
    embedBusy.value = ''
  }
}

async function testEmbedConfig() {
  embedBusy.value = 'test'
  embedMessage.value = ''
  embedError.value = ''
  try {
    const result = await testEmbed(embedPayload())
    if (result.success) {
      embedMessage.value = `连接成功：${result.message || 'ok'}`
    } else {
      embedError.value = result.message || '连接失败'
    }
  } catch (e) {
    embedError.value = errMessage(e)
  } finally {
    embedBusy.value = ''
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
  if (key === 'ask') {
    // 侧栏点「智能问数」始终进内置应用，不沿用广场选中的 appId
    askAppId.value = ''
  }
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
  <SetupView v-if="gate === 'setup'" @signed-in="onSignedIn" />
  <LoginView v-else-if="gate === 'login'" :notice="loginNotice" @signed-in="onSignedIn" />
  <div v-else-if="gate === 'checking'" class="auth-gate__checking" role="status" aria-live="polite">
    正在连接网关…
  </div>
  <div v-else-if="gate === 'ready'" class="shell">
    <a class="skip-link" href="#main-stage">跳到主内容</a>
    <nav class="rail" aria-label="模块">
      <div class="brand" title="跨源联邦查询">联邦</div>
      <button
        v-for="item in modules"
        :key="item.key"
        class="module"
        :class="{ active: activeKey === item.key }"
        type="button"
        :title="item.label"
        :aria-label="item.label"
        :aria-current="activeKey === item.key ? 'page' : undefined"
        @click="open(item.key)"
      >
        <span class="glyph" aria-hidden="true">{{ item.icon }}</span>
        <span class="name">{{ item.short }}</span>
      </button>
      <div class="rail-foot">
        <button class="module" type="button" :title="authLabel" :aria-label="authLabel" @click="openAuth">
          <span class="glyph" aria-hidden="true">⚷</span>
          <span class="name">{{ authShort }}</span>
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
        <AskView v-if="activeKey === 'ask'" :key="askAppId || 'ask-builtin'" :app-id="askAppId" />
        <PublishedAppsView
          v-else-if="activeKey === 'plaza'"
          :key="'plaza'"
          @open-app="openAskApp"
        />
        <AppsView v-else-if="activeKey === 'apps'" :key="'apps'" @open-app="openAskApp" />
        <component :is="current" v-else :key="activeKey" />
      </KeepAlive>
    </main>

    <div v-if="showAuth" class="mask" @click.self="showAuth = false">
      <form
        v-if="authUser?.localAccount"
        ref="authDialog"
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="auth-title"
        @submit.prevent="changePassword"
      >
        <h3 id="auth-title">访问身份</h3>
        <p class="hint">
          当前用户 {{ authUser.displayName || authUser.name }}（{{ authUser.name }}） · 角色
          {{ (authUser.roles || []).join(', ') || '无' }}
          · Catalog ACL {{ authUser.catalogAcl ? (authUser.catalogs || []).join(', ') || '无' : '未启用' }}
        </p>
        <label>
          原密码
          <input v-model="passwordForm.oldPassword" type="password" required autocomplete="current-password" />
        </label>
        <label>
          新密码
          <input
            v-model="passwordForm.newPassword"
            type="password"
            required
            minlength="8"
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </label>
        <p v-if="authMessage" class="ok" role="status">{{ authMessage }}</p>
        <p v-if="authError" class="err" role="alert">{{ authError }}</p>
        <div class="actions">
          <button type="button" @click="logoutAuth">退出登录</button>
          <button type="button" @click="showAuth = false">关闭</button>
          <button class="primary" type="submit" :disabled="authBusy">{{ authBusy ? '保存中…' : '修改密码' }}</button>
        </div>
      </form>
      <form
        v-else
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
          <input
            v-model="llmForm.model"
            list="llm-model-presets"
            required
            autocomplete="off"
            placeholder="例如 gpt-4.1、claude-sonnet-4-5、grok-4.7、本地模型名"
          />
          <datalist id="llm-model-presets">
            <option v-for="item in AGENT_MODELS" :key="item.id" :value="item.id">{{ item.label }}</option>
          </datalist>
        </label>
        <div class="preset-row" role="group" aria-label="快捷填入">
          <span class="hint">快捷填入</span>
          <button
            v-for="item in AGENT_MODELS"
            :key="item.id"
            class="tiny"
            type="button"
            @click="applyModel(item.id)"
          >
            {{ item.label }}
          </button>
        </div>
        <label>
          协议
          <select v-model="llmForm.protocol">
            <option value="openai">OpenAI 兼容</option>
            <option value="anthropic">Anthropic 兼容</option>
          </select>
        </label>
        <label>
          接口地址
          <input v-model="llmForm.baseUrl" required placeholder="https://api.openai.com/v1 或 http://127.0.0.1:11434/v1" />
        </label>
        <label>
          API Key
          <input v-model="llmForm.apiKey" type="password" :placeholder="llm?.apiKeySet ? '已保存，留空则不修改' : '供应商发放的密钥；本地服务可留空后保存再测'" />
        </label>
        <p class="hint">模型名、协议、地址均可手写；快捷填入只帮你带出常见组合。保存在 MySQL federal.fed_llm，立即生效。密钥不会回传到页面。</p>
        <p v-if="llmMessage" class="ok">{{ llmMessage }}</p>
        <p v-if="llmError" class="err">{{ llmError }}</p>
        <div class="actions">
          <button type="button" :disabled="!!llmBusy" @click="testLlmConfig">{{ llmBusy === 'test' ? '测试中…' : '测试连接' }}</button>
          <button type="button" @click="showLlm = false">关闭</button>
          <button class="primary" type="submit" :disabled="!!llmBusy">{{ llmBusy === 'save' ? '保存中…' : '保存对话模型' }}</button>
        </div>

        <h3 id="embed-title" style="margin-top: 18px">嵌入模型</h3>
        <p class="hint">
          知识库向量召回用（OpenAI 兼容 embeddings）。
          <template v-if="embed?.milvus">已接 Milvus{{ embed.dim ? ` · dim ${embed.dim}` : '' }}。</template>
          保存在 federal.fed_ask_embed，立即生效。
        </p>
        <label>
          嵌入模型
          <input
            v-model="embedForm.model"
            list="embed-model-presets"
            autocomplete="off"
            placeholder="例如 embedding-3、text-embedding-3-small"
          />
          <datalist id="embed-model-presets">
            <option v-for="item in EMBED_PRESETS" :key="item.id" :value="item.model">{{ item.label }}</option>
          </datalist>
        </label>
        <div class="preset-row" role="group" aria-label="嵌入快捷填入">
          <span class="hint">快捷填入</span>
          <button
            v-for="item in EMBED_PRESETS"
            :key="item.id"
            class="tiny"
            type="button"
            @click="applyEmbedPreset(item.id)"
          >
            {{ item.label }}
          </button>
        </div>
        <label>
          嵌入接口
          <input
            v-model="embedForm.endpoint"
            placeholder="https://open.bigmodel.cn/api/paas/v4/embeddings"
          />
        </label>
        <label>
          嵌入 API Key
          <input
            v-model="embedForm.apiKey"
            type="password"
            :placeholder="embed?.apiKeySet ? '已保存，留空则不修改' : '可与对话模型相同；本地服务可留空'"
          />
        </label>
        <p v-if="embedMessage" class="ok">{{ embedMessage }}</p>
        <p v-if="embedError" class="err">{{ embedError }}</p>
        <div class="actions">
          <button type="button" :disabled="!!embedBusy" @click="testEmbedConfig">
            {{ embedBusy === 'test' ? '测试中…' : '测试嵌入' }}
          </button>
          <button type="button" class="primary" :disabled="!!embedBusy" @click="saveEmbedConfig">
            {{ embedBusy === 'save' ? '保存中…' : '保存嵌入模型' }}
          </button>
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
  max-width: 54px;
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.preset-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.preset-row .hint {
  margin-right: 2px;
}
</style>
