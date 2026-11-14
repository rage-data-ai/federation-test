<script setup lang="ts">
import { ref } from 'vue'
import AskAnswerClarify from './components/ask/AskAnswerClarify.vue'
import AskFeedback from './components/ask/AskFeedback.vue'
import AskKnowledge from './components/ask/AskKnowledge.vue'
import AskPrompts from './components/ask/AskPrompts.vue'
import AskSuggestions from './components/ask/AskSuggestions.vue'
import {
  SAMPLES,
  STAGE_LABEL,
  SQL_STATUS,
  lastStage,
  useAskStream
} from './composables/useAskStream'
import { useAskLlmStatus } from './composables/useAskLlmStatus'

const { status, statusError, ready, needsKey, openLlmSettings } = useAskLlmStatus()
const { turns, draft, busy, scroller, send, stop, clearTurns, onKey } = useAskStream()

const VIEWS = [
  { key: 'chat', label: '对话' },
  { key: 'suggestions', label: '建议' },
  { key: 'knowledge', label: '知识库' },
  { key: 'prompts', label: '提示词' }
] as const
const view = ref<(typeof VIEWS)[number]['key']>('chat')
</script>

<template>
  <section class="ask" aria-labelledby="ask-title">
    <header class="module-head">
      <div class="head-main">
        <h1 id="ask-title">智能问数</h1>
        <nav class="views" aria-label="问数视图">
          <button
            v-for="item in VIEWS"
            :key="item.key"
            class="ghost"
            :class="{ active: view === item.key }"
            type="button"
            :aria-current="view === item.key ? 'page' : undefined"
            @click="view = item.key"
          >
            {{ item.label }}
          </button>
        </nav>
      </div>
      <div class="head-side" :class="{ offstage: view !== 'chat' }">
        <button v-if="status && !ready" class="link" type="button" @click="openLlmSettings">{{ needsKey ? '填写 API Key' : '未配置，去设置' }}</button>
        <span v-else-if="status" class="head-note">{{ status.protocol }} · {{ status.model }}</span>
        <button type="button" :disabled="busy || !turns.length" @click="clearTurns">新对话</button>
      </div>
    </header>
    <AskSuggestions v-if="view === 'suggestions'" />
    <AskKnowledge v-else-if="view === 'knowledge'" />
    <AskPrompts v-else-if="view === 'prompts'" />
    <div
      v-show="view === 'chat'"
      ref="scroller"
      class="thread"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      :aria-busy="busy ? 'true' : undefined"
      aria-label="问数对话记录"
    >
      <div v-if="!turns.length" class="welcome">
        <h2>智能问数</h2>
        <p class="hint">用业务语言提问，系统按 本体 → 元数据 → SQL 的顺序检索、校验并执行，口径缺失时会先问你。</p>
        <p v-if="statusError" class="err" role="alert">{{ statusError }}</p>
        <p v-else-if="needsKey" class="err" role="status">
          已选择 {{ status?.model }}，还差 API Key。
          <button class="link" type="button" @click="openLlmSettings">去填写</button>
        </p>
        <p v-else-if="status && !ready" class="err" role="status">
          还没有配置大模型。
          <button class="link" type="button" @click="openLlmSettings">去配置</button>
        </p>
        <div class="samples" role="group" aria-label="默认问题">
          <p class="hint" id="ask-samples-hint">默认问题</p>
          <button
            v-for="item in SAMPLES"
            :key="item"
            class="ghost"
            type="button"
            :disabled="!ready"
            :aria-describedby="'ask-samples-hint'"
            @click="send(item)"
          >
            {{ item }}
          </button>
        </div>
      </div>

      <article v-for="(turn, idx) in turns" :key="idx" class="turn" :aria-label="`回合 ${idx + 1}`">
        <p class="question">{{ turn.question }}</p>

        <div class="reply">
          <div class="trace-head">
            <button
              class="link"
              type="button"
              :aria-expanded="turn.showTrace ? 'true' : 'false'"
              :aria-controls="`ask-trace-${idx}`"
              @click="turn.showTrace = !turn.showTrace"
            >
              {{ turn.showTrace ? '收起过程' : '展开过程' }}
            </button>
            <span v-if="!turn.done" class="live" role="status" aria-live="polite">{{ lastStage(turn) }}</span>
          </div>

          <div v-if="turn.showTrace" :id="`ask-trace-${idx}`" class="trace">
            <section v-if="turn.answerability" class="step">
              <h4>
                <span class="tag">可答性</span>
                {{ turn.answerability.answerable === false ? '覆盖不足' : '可继续' }}
                <em v-if="turn.answerability.coverage != null">· coverage {{ turn.answerability.coverage }}</em>
              </h4>
              <p v-if="Array.isArray(turn.answerability.gaps) && turn.answerability.gaps.length" class="line warn">
                缺口：{{ (turn.answerability.gaps as string[]).join('；') }}
              </p>
              <p v-if="typeof turn.answerability.suggestion === 'string'" class="line">{{ turn.answerability.suggestion }}</p>
            </section>
            <section v-if="turn.knowledge" class="step">
              <h4>
                <span class="tag">{{ STAGE_LABEL.knowledge }}</span>
                参考知识
                <em>{{ turn.knowledge.matches.length }} 条</em>
              </h4>
              <ul class="chips">
                <li v-for="(hit, kidx) in turn.knowledge.matches" :key="hit.id ?? kidx">
                  {{ hit.question || `知识 ${hit.id ?? kidx + 1}` }}
                  <em v-if="hit.score != null">score {{ hit.score }}</em>
                </li>
              </ul>
            </section>
            <section v-if="turn.ontology" class="step">
              <h4>
                <span class="tag">{{ STAGE_LABEL.ontology }}</span>
                {{ turn.ontology.mode === 'explored' ? '多轮工具探索' : '确定性检索' }}
                <em>status={{ turn.ontology.status }} · 置信度 {{ turn.ontology.confidence }}</em>
                <em v-if="turn.ontology.recovery">· 恢复检索</em>
              </h4>
              <ul class="chips">
                <li v-for="hit in turn.ontology.matches || []" :key="hit.name">
                  「{{ hit.matchedText }}」→ {{ hit.labelZh || hit.name }} <em>{{ hit.kind }} {{ hit.score }}</em>
                </li>
              </ul>
              <p v-for="d in turn.ontology.definedConcepts || []" :key="d.name" class="line">
                派生概念 {{ d.labelZh }}：{{ d.sqlCondition || d.definitionText || (d.conditions || []).map((c) => `${c.attribute} ${c.operator} ${c.value}`).join(' 且 ') }}
              </p>
              <p v-for="(path, pidx) in turn.ontology.paths || []" :key="pidx" class="line">
                路径 {{ path.from }} → {{ path.to }}：<code v-for="step in path.steps || []" :key="step.relation">{{ step.join }}</code>
              </p>
              <p v-for="w in [...(turn.ontology.warnings || []), ...(turn.ontology.unresolved || [])]" :key="w" class="line warn">{{ w }}</p>
            </section>

            <section v-if="turn.metadata" class="step">
              <h4><span class="tag">{{ STAGE_LABEL.metadata }}</span>核对映射表 <em v-if="turn.metadata.recovery">· 已重采</em></h4>
              <p v-for="t in turn.metadata.tables || []" :key="t.table" class="line">
                <code>{{ t.table }}</code>（{{ t.concept }}）映射列 {{ (t.mappedColumns || []).map((c) => c.name).join('、') || '无' }}
                <em v-if="t.timeColumn">· 大表，时间列 {{ t.timeColumn }}</em>
              </p>
              <p v-for="issue in turn.metadata.issues || []" :key="issue" class="line warn">{{ issue }}</p>
            </section>

            <section v-for="(item, sidx) in turn.sqls" :key="`s${sidx}`" class="step">
              <h4>
                <span class="tag">SQL</span>
                {{ item.purpose === 'diagnostic' ? '诊断查询' : '主查询' }}
                <em :class="item.status">{{ (item.status && SQL_STATUS[item.status]) || item.status }}</em>
              </h4>
              <pre class="sql">{{ item.sql }}</pre>
              <p v-if="item.message" class="line warn">{{ item.message }}</p>
            </section>
          </div>

          <p v-if="turn.error" class="err banner" role="alert">{{ turn.error }}</p>

          <AskAnswerClarify
            :clarify="turn.clarify"
            :answer="turn.answer"
            :busy="busy"
            :can-reply="idx === turns.length - 1"
            @pick="send"
          />
          <AskFeedback
            v-if="turn.done && turn.runId"
            :run-id="turn.runId"
            :answered="!!turn.answer"
            :rating="turn.rating"
            @rated="turn.rating = $event"
          />
        </div>
      </article>
    </div>

    <footer v-show="view === 'chat'" class="composer" role="region" aria-label="提问区">
      <label class="sr-only" for="ask-draft">问题输入</label>
      <textarea
        id="ask-draft"
        v-model="draft"
        rows="2"
        :disabled="!ready && !!status"
        placeholder="用业务语言提问。Enter 发送，Shift+Enter 换行"
        aria-label="问题输入"
        :aria-invalid="status && !ready ? 'true' : undefined"
        @keydown="onKey"
      />
      <div class="composer-actions">
        <button v-if="busy" type="button" aria-label="停止问数" @click="stop">停止</button>
        <button
          v-else
          class="primary"
          type="button"
          :disabled="!draft.trim() || (!ready && !!status)"
          aria-label="发送问题"
          @click="send()"
        >
          发送
        </button>
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

.head-main {
  display: flex;
  align-items: center;
  gap: 16px;
}

/* 切到建议时只是藏起来、仍然占位，标题栏高度不跟着跳。 */
.offstage {
  visibility: hidden;
}

.views {
  display: flex;
  gap: 2px;
}

.views button {
  padding: 4px 12px;
  color: var(--muted);
  font-size: 13px;
}

.views button.active {
  background: var(--panel-2);
  color: var(--text);
  font-weight: 600;
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

.line code {
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
