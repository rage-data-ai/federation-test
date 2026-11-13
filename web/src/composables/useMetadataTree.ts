import { computed, nextTick, ref, type Ref } from 'vue'

const TABLE_CAP = 80

export type MetadataColumn = {
  name: string
  type?: string
  [key: string]: unknown
}

export type MetadataTable = {
  catalog: string
  schema: string
  table: string
  columns?: MetadataColumn[]
  [key: string]: unknown
}

export type CatalogItem = {
  name: string
  [key: string]: unknown
}

export type MetadataTreeOptions = {
  catalogs: Ref<CatalogItem[]>
  snapshots: Ref<MetadataTable[]>
  sql: Ref<string>
  sqlBox: Ref<HTMLTextAreaElement | HTMLInputElement | null>
  clearMenu?: () => void
}

function columnHits(item: MetadataTable, q: string): MetadataColumn[] {
  return (item.columns || []).filter(
    (col) => col.name.toLowerCase().includes(q) || String(col.type || '').toLowerCase().includes(q)
  )
}

export function quoteIdent(name: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${String(name).replaceAll('"', '""')}"`
}

export function tableKey(item: Pick<MetadataTable, 'catalog' | 'schema' | 'table'>): string {
  return `${item.catalog}.${item.schema}.${item.table}`
}

export function schemaKey(catalog: string, schema: string): string {
  return `${catalog}.${schema}`
}

export function qualifiedTable(item: Pick<MetadataTable, 'catalog' | 'schema' | 'table'>): string {
  return `${quoteIdent(item.catalog)}.${quoteIdent(item.schema)}.${quoteIdent(item.table)}`
}

/**
 * Metadata tree: filter/cap, open-state toggles, and SQL insert helpers.
 */
export function useMetadataTree({ catalogs, snapshots, sql, sqlBox, clearMenu }: MetadataTreeOptions) {
  const openCatalogs = ref<Record<string, boolean>>({})
  const openSchemas = ref<Record<string, boolean>>({})
  const openTables = ref<Record<string, boolean>>({})
  const metaQuery = ref('')

  const tree = computed(() => {
    const q = metaQuery.value.trim().toLowerCase()
    const byCatalog: Record<
      string,
      {
        name: string
        source: CatalogItem | null
        schemas: Record<string, { name: string; tables: MetadataTable[] }>
        tableCount: number
        matched: number
      }
    > = {}
    const order: string[] = []
    const ensure = (name: string, source: CatalogItem | null) => {
      if (!byCatalog[name]) {
        byCatalog[name] = { name, source, schemas: {}, tableCount: 0, matched: 0 }
        order.push(name)
      }
      return byCatalog[name]
    }
    for (const item of catalogs.value) {
      ensure(item.name, item)
    }
    for (const item of snapshots.value) {
      const cat = ensure(item.catalog, null)
      cat.tableCount += 1
      const hits = q ? columnHits(item, q) : []
      const selfHit =
        !q ||
        item.catalog.toLowerCase().includes(q) ||
        item.schema.toLowerCase().includes(q) ||
        item.table.toLowerCase().includes(q)
      if (!selfHit && !hits.length) {
        continue
      }
      const sch = cat.schemas[item.schema] || (cat.schemas[item.schema] = { name: item.schema, tables: [] })
      sch.tables.push({ ...item, columns: q && hits.length && !selfHit ? hits : item.columns || [] })
      cat.matched += 1
    }
    const cap = q ? 200 : TABLE_CAP
    return order
      .map((name) => byCatalog[name])
      .filter((cat) => !q || cat.matched || cat.name.toLowerCase().includes(q))
      .map((cat) => ({
        name: cat.name,
        source: cat.source,
        tableCount: cat.tableCount,
        schemas: Object.values(cat.schemas).map((sch) => ({
          name: sch.name,
          tables: sch.tables.slice(0, cap),
          hidden: Math.max(0, sch.tables.length - cap)
        }))
      }))
  })

  function isCatalogOpen(name: string) {
    if (metaQuery.value.trim()) {
      return true
    }
    return openCatalogs.value[name] !== false
  }

  function isSchemaOpen(catalog: string, schema: string) {
    return !!metaQuery.value.trim() || !!openSchemas.value[schemaKey(catalog, schema)]
  }

  function isTableOpen(item: MetadataTable) {
    if (openTables.value[tableKey(item)]) {
      return true
    }
    const q = metaQuery.value.trim().toLowerCase()
    return !!q && columnHits(item, q).length > 0
  }

  function toggleCatalogNode(name: string) {
    openCatalogs.value = { ...openCatalogs.value, [name]: !isCatalogOpen(name) }
  }

  function toggleSchema(catalog: string, schema: string) {
    const key = schemaKey(catalog, schema)
    openSchemas.value = { ...openSchemas.value, [key]: !isSchemaOpen(catalog, schema) }
  }

  function toggleTable(item: MetadataTable) {
    const key = tableKey(item)
    openTables.value = { ...openTables.value, [key]: !openTables.value[key] }
  }

  function insertAtCursor(text: string) {
    const el = sqlBox.value
    const current = sql.value || ''
    if (!el) {
      sql.value = current && !/\s$/.test(current) ? `${current} ${text}` : `${current}${text}`
      return
    }
    const start = el.selectionStart ?? current.length
    const end = el.selectionEnd ?? current.length
    const before = current.slice(0, start)
    const after = current.slice(end)
    const pad = before && !/\s$/.test(before) ? ' ' : ''
    sql.value = `${before}${pad}${text}${after}`
    const pos = start + pad.length + text.length
    nextTick(() => {
      el.focus()
      el.setSelectionRange(pos, pos)
    })
  }

  function useTable(item: MetadataTable) {
    const cols = item.columns?.length ? item.columns.map((col) => quoteIdent(col.name)).join(', ') : '*'
    sql.value = `SELECT ${cols}\nFROM ${qualifiedTable(item)}\nLIMIT 20`
  }

  function insertTable(item: MetadataTable) {
    clearMenu?.()
    insertAtCursor(qualifiedTable(item))
  }

  function insertColumn(item: MetadataTable, name: string) {
    insertAtCursor(`${quoteIdent(item.table)}.${quoteIdent(name)}`)
  }

  return {
    openCatalogs,
    openSchemas,
    openTables,
    metaQuery,
    tree,
    isCatalogOpen,
    isSchemaOpen,
    isTableOpen,
    toggleCatalogNode,
    toggleSchema,
    toggleTable,
    insertAtCursor,
    useTable,
    insertTable,
    insertColumn,
    tableKey,
    schemaKey,
    qualifiedTable
  }
}
