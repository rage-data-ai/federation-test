export const SOURCE_TYPES = [
  { value: 'mysql', label: 'MySQL', port: 3306, route: 'TRINO' },
  { value: 'postgresql', label: 'PostgreSQL', port: 5432, route: 'TRINO' },
  { value: 'clickhouse', label: 'ClickHouse', port: 8123, route: 'TRINO' },
  { value: 'doris', label: 'Doris', port: 9030, route: 'TRINO' },
  { value: 'elasticsearch', label: 'Elasticsearch', port: 9200, route: 'TRINO' },
  { value: 'kafka', label: 'Kafka', port: 9092, route: 'TRINO' }
]

export function emptyCatalog() {
  return {
    name: '',
    type: 'mysql',
    host: '127.0.0.1',
    port: 3306,
    database: '',
    username: '',
    password: '',
    url: '',
    extra: '',
    tls: false
  }
}

export function isTrinoSource() {
  return true
}

export function typeLabel(type) {
  return SOURCE_TYPES.find((item) => item.value === type)?.label || type
}

export function sampleSql(catalog) {
  if (catalog.type === 'kafka') {
    const schema = catalog.database || 'default'
    const topic = (catalog.extra || '').split(',')[0].trim() || '<topic>'
    const table = topic.includes('.') ? topic.split('.').pop() : topic
    const quoted = /[^A-Za-z0-9_]/.test(table) ? `"${table}"` : table
    return `SELECT _partition_id, _partition_offset, _message\nFROM ${catalog.name}.${schema}.${quoted}\nLIMIT 20`
  }
  if (catalog.type === 'elasticsearch') {
    const schema = catalog.database || 'default'
    return `SHOW TABLES FROM ${catalog.name}.${schema}`
  }
  const schema = catalog.database || '<schema>'
  return `SELECT *\nFROM ${catalog.name}.${schema}.<table>\nLIMIT 20`
}
