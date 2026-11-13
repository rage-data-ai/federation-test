import { computed, nextTick, ref } from 'vue'

const TABLE_CAP = 80

function columnHits(item, q) {
  return (item.columns || []).filter(
    (col) => col.name.toLowerCase().includes(q) || String(col.type || '').toLowerCase().includes(q)
  )
}

export function quoteIdent(name) {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${String(name).replaceAll('"', '""')}"`
}

export function tableKey(item) {
  return `${item.catalog}.${item.schema}.${item.table}`
}

export function schemaKey(catalog, schema) {
  return `${catalog}.${schema}`
}

export function qualifiedTable(item) {
  return `${quoteIdent(item.catalog)}.${quoteIdent(item.schema)}.${quoteIdent(item.table)}`
}

/**
 * Metadata tree: filter/cap, open-state toggles, and SQL insert helpers.
 * @param {{ catalogs: import('vue').Ref, snapshots: import('vue').Ref, sql: import('vue').Ref, sqlBox: import('vue').Ref, clearMenu?: () => void }} opts
 */
export function useMetadataTree({ catalogs, snapshots, sql, sqlBox, clearMenu }) {
  const openCatalogs = ref({})
  const openSchemas = ref({})
  const openTables = ref({})
  const metaQuery = ref('')

  const tree = computed(() => {
    const q = metaQuery.value.trim().toLowerCase()
    const byCatalog = {}
    const order = []
    const ensure = (name, source) => {
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

  function isCatalogOpen(name) {
    if (metaQuery.value.trim()) {
      return true
    }
    return openCatalogs.value[name] !== false
  }

  function isSchemaOpen(catalog, schema) {
    return !!metaQuery.value.trim() || !!openSchemas.value[schemaKey(catalog, schema)]
  }

  function isTableOpen(item) {
    if (openTables.value[tableKey(item)]) {
      return true
    }
    const q = metaQuery.value.trim().toLowerCase()
    return !!q && columnHits(item, q).length > 0
  }

  function toggleCatalogNode(name) {
    openCatalogs.value = { ...openCatalogs.value, [name]: !isCatalogOpen(name) }
  }

  function toggleSchema(catalog, schema) {
    const key = schemaKey(catalog, schema)
    openSchemas.value = { ...openSchemas.value, [key]: !isSchemaOpen(catalog, schema) }
  }

  function toggleTable(item) {
    const key = tableKey(item)
    openTables.value = { ...openTables.value, [key]: !openTables.value[key] }
  }

  function insertAtCursor(text) {
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

  function useTable(item) {
    const cols = item.columns?.length ? item.columns.map((col) => quoteIdent(col.name)).join(', ') : '*'
    sql.value = `SELECT ${cols}\nFROM ${qualifiedTable(item)}\nLIMIT 20`
  }

  function insertTable(item) {
    clearMenu?.()
    insertAtCursor(qualifiedTable(item))
  }

  function insertColumn(item, name) {
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
