import { reactive, ref, type Ref } from 'vue'
import { createCatalog, deleteCatalog, testCatalog, updateCatalog } from '../api'
import { SOURCE_TYPES, emptyCatalog } from '../examples'

export type CatalogForm = ReturnType<typeof emptyCatalog>

export type CatalogRecord = {
  name: string
  type: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
  url?: string
  extra?: string
  tls?: boolean
  [key: string]: unknown
}

export type CatalogFormOptions = {
  onSaved?: () => void | Promise<void>
  flashError?: (message: string) => void
  clearMenu?: () => void
  openCatalogs?: Ref<Record<string, boolean>>
}

/**
 * Catalog create/edit form state and actions.
 * Inject loaders and side-effects so the view stays the orchestrator.
 */
export function useCatalogForm({ onSaved, flashError, clearMenu, openCatalogs }: CatalogFormOptions) {
  const showForm = ref(false)
  const editing = ref(false)
  const form = reactive(emptyCatalog()) as CatalogForm
  const formMessage = ref('')
  const formError = ref('')
  const saving = ref(false)

  function onTypeChange(type?: string) {
    if (type) {
      form.type = type
    }
    const found = SOURCE_TYPES.find((item) => item.value === form.type)
    if (found) {
      form.port = found.port
    }
  }

  function openCreate() {
    Object.assign(form, emptyCatalog())
    editing.value = false
    formMessage.value = ''
    formError.value = ''
    showForm.value = true
  }

  function openEdit(catalog: CatalogRecord) {
    clearMenu?.()
    Object.assign(form, emptyCatalog(), {
      name: catalog.name,
      type: catalog.type,
      host: catalog.host || '127.0.0.1',
      port: catalog.port || SOURCE_TYPES.find((item) => item.value === catalog.type)?.port || 3306,
      database: catalog.database || '',
      username: catalog.username || '',
      password: '',
      url: catalog.url || '',
      extra: catalog.extra || '',
      tls: !!catalog.tls
    })
    editing.value = true
    formMessage.value = ''
    formError.value = ''
    showForm.value = true
  }

  function payload(source: CatalogForm = form) {
    return {
      name: source.name.trim(),
      type: source.type,
      route: 'TRINO',
      host: source.host.trim(),
      port: Number(source.port),
      database: source.database.trim(),
      username: source.username.trim(),
      password: source.password,
      url: (source.url || '').trim(),
      extra: (source.extra || '').trim(),
      tls: !!source.tls
    }
  }

  function applyDraft(draft?: Partial<CatalogForm> | null) {
    if (draft) {
      Object.assign(form, emptyCatalog(), draft)
    }
  }

  async function testConnection(draft?: Partial<CatalogForm> | null) {
    applyDraft(draft)
    formMessage.value = ''
    formError.value = ''
    try {
      const data = (await testCatalog(payload())) as { success?: boolean; message?: string }
      if (data.success) {
        formMessage.value = data.message || ''
      } else {
        formError.value = data.message || '连接失败'
      }
    } catch (e) {
      formError.value = (e instanceof Error ? e.message : String(e)) || '连接失败'
    }
  }

  async function saveCatalog(draft?: Partial<CatalogForm> | null) {
    applyDraft(draft)
    formMessage.value = ''
    formError.value = ''
    saving.value = true
    try {
      const body = payload()
      if (editing.value) {
        await updateCatalog(body.name, body)
      } else {
        await createCatalog(body)
      }
      showForm.value = false
      if (openCatalogs) {
        openCatalogs.value = { ...openCatalogs.value, [body.name]: true }
      }
      await onSaved?.()
    } catch (e) {
      formError.value = (e instanceof Error ? e.message : String(e)) || '保存失败'
    } finally {
      saving.value = false
    }
  }

  async function removeCatalog(catalog: CatalogRecord) {
    clearMenu?.()
    if (!confirm(`删除数据源 ${catalog.name}？`)) {
      return
    }
    try {
      await deleteCatalog(catalog.name)
      await onSaved?.()
    } catch (e) {
      flashError?.((e instanceof Error ? e.message : String(e)) || '删除失败')
    }
  }

  return {
    showForm,
    editing,
    form,
    formMessage,
    formError,
    saving,
    onTypeChange,
    openCreate,
    openEdit,
    testConnection,
    saveCatalog,
    removeCatalog
  }
}
