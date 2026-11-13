<script setup lang="ts">
import { reactive, ref, toRef, watch } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'
import { SOURCE_TYPES, emptyCatalog } from '../../examples'

const props = defineProps({
  show: { type: Boolean, required: true },
  editing: { type: Boolean, required: true },
  form: { type: Object, required: true },
  formMessage: { type: String, default: '' },
  formError: { type: String, default: '' },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'save', 'test', 'type-change'])

const local = reactive(emptyCatalog())
const dialog = ref<HTMLElement | null>(null)
const open = toRef(props, 'show')
useFocusTrap(open, dialog, () => emit('close'))

watch(
  () => props.show,
  (isOpen) => {
    if (isOpen) {
      Object.assign(local, emptyCatalog(), props.form)
    }
  },
  { immediate: true }
)

function onTypeChange() {
  const found = SOURCE_TYPES.find((item) => item.value === local.type)
  if (found) {
    local.port = found.port
  }
  emit('type-change', local.type)
}

function snapshot() {
  return { ...local }
}
</script>

<template>
  <div v-if="show" class="mask" @click.self="emit('close')">
    <form
      ref="dialog"
      class="dialog"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="editing ? 'catalog-edit-title' : 'catalog-add-title'"
      @submit.prevent="emit('save', snapshot())"
    >
      <h3 :id="editing ? 'catalog-edit-title' : 'catalog-add-title'">
        {{ editing ? '编辑数据源' : '添加数据源' }}
      </h3>
      <label>
        名称（SQL 里的 catalog）
        <input v-model="local.name" :disabled="editing" required placeholder="例如 mysql70 或 es81" />
      </label>
      <label>
        类型
        <select v-model="local.type" @change="onTypeChange">
          <option v-for="item in SOURCE_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
      </label>
      <div class="pair">
        <label>
          主机
          <input v-model="local.host" required placeholder="127.0.0.1" />
        </label>
        <label>
          端口
          <input v-model.number="local.port" type="number" required />
        </label>
      </div>
      <label>
        {{ local.type === 'elasticsearch' ? '默认 Schema' : local.type === 'kafka' ? 'Kafka Schema' : '数据库 / Schema' }}
        <input
          v-model="local.database"
          :placeholder="local.type === 'kafka' || local.type === 'elasticsearch' ? 'default' : '例如 shop'"
        />
      </label>
      <label v-if="local.type === 'kafka'">
        Topic 列表（逗号分隔，可后补）
        <input v-model="local.extra" placeholder="orders,my-topic" />
      </label>
      <label v-if="local.type === 'elasticsearch'" class="check">
        <input v-model="local.tls" type="checkbox" />
        启用 TLS
      </label>
      <div class="pair">
        <label>
          用户名
          <input v-model="local.username" />
        </label>
        <label>
          密码
          <input v-model="local.password" type="password" :placeholder="editing ? '留空则不修改' : ''" />
        </label>
      </div>
      <p class="hint">
        保存后由 Trino 动态加载，无需重启。Kafka 填用户名密码即 SASL_PLAINTEXT/PLAIN。
      </p>
      <p v-if="formMessage" class="ok" role="status">{{ formMessage }}</p>
      <p v-if="formError" class="err" role="alert">{{ formError }}</p>
      <div class="actions">
        <button type="button" @click="emit('test', snapshot())">测试连接</button>
        <button type="button" @click="emit('close')">取消</button>
        <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </form>
  </div>
</template>
