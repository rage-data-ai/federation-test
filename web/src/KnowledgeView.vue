<script setup lang="ts">
import AskKnowledge from './components/ask/AskKnowledge.vue'
</script>

<template>
  <section class="knowledge-module" aria-labelledby="knowledge-title">
    <header class="module-head">
      <div class="head-main">
        <h1 id="knowledge-title">知识库</h1>
        <p class="hint">维护通用与智能问数知识；Milvus 可选，不可用时自动回退内存检索。</p>
      </div>
    </header>
    <AskKnowledge />
  </section>
</template>

<style scoped>
.knowledge-module {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  flex: 1;
}

.hint {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
</style>
