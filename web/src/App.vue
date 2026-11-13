<script setup>
import { computed, onMounted, onUnmounted, provide, reactive, ref } from 'vue'
import { llmConfig, saveLlm, testLlm } from './api'
import AskView from './AskView.vue'
import DatasourceView from './DatasourceView.vue'
import OntologyView from './OntologyView.vue'
import { AGENT_MODELS, DEFAULT_MODEL, findModel } from './models'

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

onMounted(() => {
  applyTheme()
  themeTimer = window.setInterval(applyTheme, 60_000)
  refreshLlm()
})

onUnmounted(() => {
  window.clearInterval(themeTimer)
})

const modelOptions = computed(() => {
  if (findModel(llmForm.model) || !llmForm.model) {
    return AGENT_MODELS
  }
  return [...AGENT_MODELS, { id: llmForm.model, label: llmForm.model }]
})

async function refreshLlm() {
  try {
    llm.value = await llmConfig()
  } catch {
    llm.value = null
  }
}

function applyModel(id) {
  const preset = findModel(id)
  if (!preset) {
    return
  }
  llmForm.model = preset.id
  llmForm.protocol = preset.protocol
  llmForm.baseUrl = preset.baseUrl
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
        <button class="module" :class="{ pending: llm && (!llm.configured || !llm.apiKeySet) }" type="button" title="大模型配置" @click="openLlm">
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

    <div v-if="showLlm" class="mask" @click.self="showLlm = false">
      <form class="dialog" @submit.prevent="saveLlmConfig">
        <h3>大模型</h3>
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
