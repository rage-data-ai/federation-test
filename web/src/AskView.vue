<script setup>
import { computed, inject, nextTick, onActivated, onUnmounted, ref, watch } from 'vue'
import { askStream, llmConfig } from './api'

const SAMPLES = [
  '哪些账户在大量不同主机上登录失败？各失败了多少次、涉及多少台主机？',
  '用网络登录（登录类型 3）成功登上很多台主机的账户有哪些？各覆盖多少台主机？',
  '哪些主机在事件日志服务停止（事件 1100）之后，又出现了进程创建（事件 4688）？这样的主机有多少台？'
]
const STAGE_LABEL = { ontology: '本体', metadata: '元数据', insight: '分析' }
const SOURCE_LABEL = { ontology: '本体定义', inferred: '推断', user: '用户指定' }
const SQL_STATUS = { executed: '已执行', rejected: '校验拒绝', failed: '执行失败' }

const turns = ref([])
const draft = ref('')
const busy = ref(false)
const status = ref(null)
const statusError = ref('')
const scroller = ref(null)
let controller = null

const openLlmSettings = inject('openLlmSettings', () => {})
const llmRevision = inject('llmRevision', ref(0))
const ready = computed(() => !!(status.value?.configured && status.value?.apiKeySet))
const needsKey = computed(() => !!(status.value?.configured && !status.value?.apiKeySet))

watch(llmRevision, loadStatus)
onActivated(loadStatus)

async function loadStatus() {
  try {
    status.value = await llmConfig()
    statusError.value = ''
  } catch (e) {
    statusError.value = e.message
  }
}

onUnmounted(() => controller?.abort())

function history() {
  const items = []
  for (const turn of turns.value) {
    items.push({ role: 'user', content: turn.question })
    const reply = turn.answer?.answer || turn.clarify?.question
    if (reply) {
      items.push({ role: 'assistant', content: reply })
    }
  }
  return items
}

async function send(text) {
  const question = (text ?? draft.value).trim()
  if (!question || busy.value) {
    return
  }
  const prior = history()
  draft.value = ''
  const turn = { question, stages: [], ontology: null, metadata: null, sqls: [], results: [], answer: null, clarify: null, error: '', done: false, showTrace: true }
  turns.value.push(turn)
  const live = turns.value[turns.value.length - 1]
  busy.value = true
  controller = new AbortController()
  scrollDown()
  try {
    await askStream({ question, history: prior }, (name, data) => onEvent(live, name, data), controller.signal)
  } catch (e) {
    live.error = e.message
  } finally {
    live.done = true
    busy.value = false
    controller = null
    if (live.answer) {
      live.showTrace = false
    }
    scrollDown()
  }
}

function onEvent(turn, name, data) {
  switch (name) {
    case 'stage':
      turn.stages.push(data)
      break
    case 'ontology':
      turn.ontology = { ...data.context, mode: data.mode, recovery: data.recovery }
      break
    case 'metadata':
      turn.metadata = { ...data.report, recovery: data.recovery }
      break
    case 'sql':
      turn.sqls.push(data)
      break
    case 'result':
      turn.results.push(data)
      break
    case 'answer':
      turn.answer = data
      break
    case 'clarify':
      turn.clarify = data
      break
    case 'error':
      turn.error = data.message
      break
    default:
      break
  }
  scrollDown()
}

function stop() {
  controller?.abort()
}

function onKey(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}

function scrollDown() {
  nextTick(() => {
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  })
}

function cell(value) {
  if (value === null || value === undefined) {
    return 'NULL'
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function lastStage(turn) {
  const stage = turn.stages[turn.stages.length - 1]
  return stage ? stage.message : '提交中…'
}
</script>

<template>
  <section class="ask">
    <header class="module-head">
      <h1>智能问数</h1>
      <div class="head-side">
        <button v-if="status && !ready" class="link" type="button" @click="openLlmSettings">{{ needsKey ? '填写 API Key' : '未配置，去设置' }}</button>
        <span v-else-if="status" class="head-note">{{ status.protocol }} · {{ status.model }}</span>
        <button type="button" :disabled="busy || !turns.length" @click="turns = []">新对话</button>
      </div>
    </header>
    <div ref="scroller" class="thread">
      <div v-if="!turns.length" class="welcome">
        <h2>智能问数</h2>
        <p class="hint">用业务语言提问，系统按 本体 → 元数据 → SQL 的顺序检索、校验并执行，口径缺失时会先问你。</p>
        <p v-if="statusError" class="err">{{ statusError }}</p>
        <p v-else-if="needsKey" class="err">
          已选择 {{ status.model }}，还差 API Key。
          <button class="link" type="button" @click="openLlmSettings">去填写</button>
        </p>
        <p v-else-if="status && !ready" class="err">
          还没有配置大模型。
          <button class="link" type="button" @click="openLlmSettings">去配置</button>
        </p>
        <div class="samples">
          <p class="hint">默认问题</p>
          <button v-for="item in SAMPLES" :key="item" class="ghost" type="button" :disabled="!ready" @click="send(item)">{{ item }}</button>
        </div>
      </div>

      <article v-for="(turn, idx) in turns" :key="idx" class="turn">
        <p class="question">{{ turn.question }}</p>

        <div class="reply">
          <div class="trace-head">
            <button class="link" type="button" @click="turn.showTrace = !turn.showTrace">
              {{ turn.showTrace ? '收起过程' : '展开过程' }}
            </button>
            <span v-if="!turn.done" class="live">{{ lastStage(turn) }}</span>
          </div>

          <div v-if="turn.showTrace" class="trace">
            <section v-if="turn.ontology" class="step">
              <h4>
                <span class="tag">{{ STAGE_LABEL.ontology }}</span>
                {{ turn.ontology.mode === 'explored' ? '多轮工具探索' : '确定性检索' }}
                <em>status={{ turn.ontology.status }} · 置信度 {{ turn.ontology.confidence }}</em>
                <em v-if="turn.ontology.recovery">· 恢复检索</em>
              </h4>
              <ul class="chips">
                <li v-for="hit in turn.ontology.matches" :key="hit.name">
                  「{{ hit.matchedText }}」→ {{ hit.labelZh || hit.name }} <em>{{ hit.kind }} {{ hit.score }}</em>
                </li>
              </ul>
              <p v-for="d in turn.ontology.definedConcepts" :key="d.name" class="line">
                派生概念 {{ d.labelZh }}：{{ d.sqlCondition || d.definitionText || d.conditions.join(' 且 ') }}
              </p>
              <p v-for="(path, pidx) in turn.ontology.paths" :key="pidx" class="line">
                路径 {{ path.from }} → {{ path.to }}：<code v-for="step in path.steps" :key="step.relation">{{ step.join }}</code>
              </p>
              <p v-for="w in [...turn.ontology.warnings, ...turn.ontology.unresolved]" :key="w" class="line warn">{{ w }}</p>
            </section>

            <section v-if="turn.metadata" class="step">
              <h4><span class="tag">{{ STAGE_LABEL.metadata }}</span>核对映射表 <em v-if="turn.metadata.recovery">· 已重采</em></h4>
              <p v-for="t in turn.metadata.tables" :key="t.table" class="line">
                <code>{{ t.table }}</code>（{{ t.concept }}）映射列 {{ t.mappedColumns.map((c) => c.name).join('、') || '无' }}
                <em v-if="t.timeColumn">· 大表，时间列 {{ t.timeColumn }}</em>
              </p>
              <p v-for="issue in turn.metadata.issues" :key="issue" class="line warn">{{ issue }}</p>
            </section>

            <section v-for="(item, sidx) in turn.sqls" :key="`s${sidx}`" class="step">
              <h4>
                <span class="tag">SQL</span>
                {{ item.purpose === 'diagnostic' ? '诊断查询' : '主查询' }}
                <em :class="item.status">{{ SQL_STATUS[item.status] || item.status }}</em>
              </h4>
              <pre class="sql">{{ item.sql }}</pre>
              <p v-if="item.message" class="line warn">{{ item.message }}</p>
            </section>
          </div>

          <p v-if="turn.error" class="err banner">{{ turn.error }}</p>

          <div v-if="turn.clarify" class="clarify">
            <p>{{ turn.clarify.question }}</p>
            <div class="options">
              <button v-for="opt in turn.clarify.options" :key="opt" type="button" :disabled="busy || idx !== turns.length - 1" @click="send(opt)">
                {{ opt }}
              </button>
            </div>
          </div>

          <div v-if="turn.answer" class="answer">
            <p class="conclusion">{{ turn.answer.answer }}</p>
            <div v-if="turn.answer.columns?.length" class="table-wrap">
              <table>
                <thead>
                  <tr><th v-for="col in turn.answer.columns" :key="col.name">{{ col.name }}</th></tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ridx) in turn.answer.rows" :key="ridx">
                    <td v-for="(value, cidx) in row" :key="cidx" :class="{ nulls: value === null }" :title="cell(value)">{{ cell(value) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-if="turn.answer.rowCount > (turn.answer.rows?.length || 0)" class="hint small">
              共 {{ turn.answer.rowCount }} 行，仅展示前 {{ turn.answer.rows.length }} 行
            </p>
            <div v-if="turn.answer.caliber?.length" class="caliber">
              <h4>口径来源</h4>
              <ul>
                <li v-for="(c, cidx) in turn.answer.caliber" :key="cidx">
                  <span class="source" :class="c.source">{{ SOURCE_LABEL[c.source] || c.source }}</span>
                  <b>{{ c.item }}</b> <span class="hint">{{ c.detail }}</span>
                </li>
              </ul>
            </div>
            <details v-if="turn.answer.sql" class="final-sql">
              <summary>最终 SQL</summary>
              <pre class="sql">{{ turn.answer.sql }}</pre>
            </details>
          </div>
        </div>
      </article>
    </div>

    <footer class="composer">
      <textarea
        v-model="draft"
        rows="2"
        :disabled="!ready && !!status"
        placeholder="用业务语言提问。Enter 发送，Shift+Enter 换行"
        @keydown="onKey"
      />
      <div class="composer-actions">
        <button v-if="busy" type="button" @click="stop">停止</button>
        <button v-else class="primary" type="button" :disabled="!draft.trim() || (!ready && !!status)" @click="send()">发送</button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.ask {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.thread {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px 24px;
}

.welcome {
  max-width: 720px;
  margin: 8vh auto 0;
  text-align: center;
}

.welcome h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.samples {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  margin-top: 20px;
  text-align: left;
}

.samples .hint {
  margin: 0;
}

.samples button {
  border-color: var(--line);
  text-align: left;
  line-height: 1.5;
  white-space: normal;
}

.turn {
  max-width: 980px;
  margin: 0 auto 22px;
}

.question {
  margin: 0 0 10px auto;
  width: fit-content;
  max-width: 80%;
  padding: 9px 14px;
  border-radius: 12px 12px 2px 12px;
  background: var(--accent);
  color: #fff;
  white-space: pre-wrap;
}

.reply {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.trace-head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.live {
  color: var(--muted);
}

.live::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1s ease-in-out infinite;
}

@keyframes pulse {
  50% { opacity: 0.3; }
}

.trace {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 12px;
  border-left: 2px solid var(--line);
}

.step h4 {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
}

.step h4 em,
.chips em {
  font-style: normal;
  font-weight: 400;
  color: var(--muted);
  font-size: 12px;
}

.step h4 em.executed { color: var(--ok); }
.step h4 em.rejected,
.step h4 em.failed { color: var(--err); }

.tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--panel-2);
  border: 1px solid var(--line);
  font-size: 11px;
  font-weight: 500;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
}

.chips li {
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--panel-2);
}

.line {
  margin: 2px 0;
  font-size: 12px;
}

.line code,
.caliber code {
  margin-right: 6px;
  font-size: 12px;
}

.warn {
  color: var(--warn);
}

.sql {
  margin: 4px 0;
  font-size: 12px;
}

.clarify,
.answer {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.clarify p {
  margin: 0 0 10px;
}

.options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.conclusion {
  margin: 0 0 10px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.small {
  margin: 6px 0 0;
  font-size: 12px;
}

.caliber h4 {
  margin: 12px 0 6px;
  font-size: 13px;
}

.caliber ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.source {
  display: inline-block;
  min-width: 56px;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  text-align: center;
  border: 1px solid var(--line);
}

.source.ontology { color: var(--ok); border-color: var(--ok); }
.source.inferred { color: var(--warn); border-color: var(--warn); }
.source.user { color: var(--accent-2); border-color: var(--accent-2); }

.final-sql {
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted);
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 10px 20px 14px;
  border-top: 1px solid var(--line);
  background: var(--panel);
}

.composer textarea {
  flex: 1;
  resize: none;
  font-family: inherit;
  line-height: 1.5;
}

.composer-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
