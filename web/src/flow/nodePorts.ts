/** 节点端口目录：由后端 FlowNodePorts 下发，前端只做缓存与引用语法辅助。 */

export type NodePort = {
  name: string
  label: string
  description: string
  dataType?: string
  fields?: NodePort[]
}

export type NodePortSpec = {
  inputs: NodePort[]
  outputs: NodePort[]
  defaultInputs: Record<string, unknown>
}

export type SysVar = { path: string; label: string }

export type FlowNodePortsCatalog = {
  sysVars: SysVar[]
  ports: Record<string, NodePortSpec>
}

const EMPTY: NodePortSpec = { inputs: [], outputs: [], defaultInputs: {} }

let portsCatalog: Record<string, NodePortSpec> = {}
let sysVarsList: SysVar[] = []
let loaded = false

export function isPortsCatalogLoaded(): boolean {
  return loaded
}

export function setPortsCatalog(catalog: FlowNodePortsCatalog | null | undefined): void {
  const ports = catalog?.ports || {}
  const normalized: Record<string, NodePortSpec> = {}
  for (const [type, spec] of Object.entries(ports)) {
    normalized[type] = {
      inputs: Array.isArray(spec?.inputs) ? spec.inputs : [],
      outputs: Array.isArray(spec?.outputs) ? spec.outputs : [],
      defaultInputs:
        spec?.defaultInputs && typeof spec.defaultInputs === 'object' ? { ...spec.defaultInputs } : {}
    }
  }
  portsCatalog = normalized
  sysVarsList = Array.isArray(catalog?.sysVars) ? catalog.sysVars.map((v) => ({ ...v })) : []
  loaded = Object.keys(portsCatalog).length > 0
}

export function portsFor(type: string): NodePortSpec {
  return portsCatalog[type] || EMPTY
}

/** 兼容旧 import；请优先用 sysVars()。 */
export const SYS_VARS: SysVar[] = []

export function sysVars(): SysVar[] {
  return sysVarsList.length ? sysVarsList : SYS_VARS
}

export function asRef(path: string): string {
  return `{{#${path}#}}`
}

export function parseRef(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const text = value.trim()
  if (text.startsWith('{{#') && text.endsWith('#}}')) {
    return text.slice(3, -3).trim()
  }
  return null
}
