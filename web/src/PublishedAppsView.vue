<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listApps, listPublishedApps } from './api'
import type { ChatApp, PublishedApp } from './types'
import { errMessage } from './types'

const BUILTIN_APP_ID = 'app-builtin-ask'

const emit = defineEmits<{ 'open-app': [appId: string] }>()

const published = ref<PublishedApp[]>([])
const catalog = ref<ChatApp[]>([])
const error = ref('')
const busy = ref(false)

const cards = computed(() => {
  const byId = new Map(catalog.value.map((app) => [app.id, app]))
  return published.value.map((app) => {
    const meta = byId.get(app.appId)
    const nodes = app.graph?.nodes?.length ?? 0
    return {
      appId: app.appId,
      name: app.appName,
      description: meta?.description?.trim() || '',
      releaseId: app.releaseId,
      nodes,
      builtin: app.appId === BUILTIN_APP_ID
    }
  })
})

const customCards = computed(() => cards.value.filter((card) => !card.builtin))
const builtinCard = computed(() => cards.value.find((card) => card.builtin) || null)

onMounted(load)

async function load() {
  busy.value = true
  error.value = ''
  try {
    const [pubs, apps] = await Promise.all([listPublishedApps(), listApps()])
    published.value = pubs
    catalog.value = apps
  } catch (e) {
    error.value = errMessage(e, '加载已发布应用失败')
  } finally {
    busy.value = false
  }
}

function openChat(appId: string) {
  emit('open-app', appId)
}
</script>

<template>
  <section class="plaza" aria-labelledby="plaza-title">
    <header class="module-head">
      <div class="head-main">
        <h1 id="plaza-title">应用广场</h1>
        <p class="hint">只展示已发布的问数应用；点「打开对话」进入该应用自己的会话。编排请去「问数应用」。</p>
      </div>
      <button type="button" :disabled="busy" @click="load">刷新</button>
    </header>

    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <p v-else-if="busy && !cards.length" class="hint pad" role="status">加载中…</p>

    <div v-else class="body">
      <section v-if="customCards.length" class="block" aria-label="已发布应用">
        <h2 class="block-title">我的已发布应用</h2>
        <ul class="grid">
          <li v-for="card in customCards" :key="card.appId">
            <article class="card">
              <header class="card-head">
                <h3>{{ card.name }}</h3>
                <span class="pill">已发布</span>
              </header>
              <p class="desc">{{ card.description || '暂无说明' }}</p>
              <p class="meta">{{ card.nodes }} 个节点 · release #{{ card.releaseId }}</p>
              <button class="primary" type="button" @click="openChat(card.appId)">打开对话</button>
            </article>
          </li>
        </ul>
      </section>

      <section v-else class="empty" role="status">
        <p>还没有自己发布的应用。</p>
        <p class="hint">在「问数应用」里创建流程并发布后，会出现在这里。</p>
      </section>

      <section v-if="builtinCard" class="block muted-block" aria-label="内置应用">
        <h2 class="block-title">内置</h2>
        <article class="card slim">
          <header class="card-head">
            <h3>{{ builtinCard.name }}</h3>
            <span class="pill soft">系统</span>
          </header>
          <p class="desc">默认智能问数流水线；也可从侧栏「智能问数」直接进入。</p>
          <button type="button" @click="openChat(builtinCard.appId)">打开对话</button>
        </article>
      </section>
    </div>
  </section>
</template>

<style scoped>
.plaza {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  min-height: 0;
  padding: 18px 22px 28px;
  overflow: auto;
}

.module-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.head-main h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.hint {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

.pad {
  padding: 8px 0;
}

.err {
  margin: 0;
  color: var(--err);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.block-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--muted);
  letter-spacing: 0.02em;
}

.grid {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel, var(--bg));
  min-height: 160px;
}

.card.slim {
  max-width: 420px;
  min-height: 0;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head h3 {
  margin: 0;
  font-size: 16px;
}

.pill {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
}

.pill.soft {
  background: var(--line);
  color: var(--muted);
}

.desc {
  margin: 0;
  flex: 1;
  font-size: 13px;
  line-height: 1.45;
  color: var(--text);
}

.meta {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.empty {
  padding: 28px 16px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  text-align: center;
}

.empty p {
  margin: 0 0 6px;
}

.muted-block {
  opacity: 0.95;
}

button.primary {
  align-self: flex-start;
  margin-top: 4px;
}
</style>
