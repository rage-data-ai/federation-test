/** Models this agent can configure on the public xAI OpenAI-compatible API. */
export type AgentModel = {
  id: string
  label: string
  protocol: string
  baseUrl: string
}

export const AGENT_MODELS: AgentModel[] = [
  {
    id: 'grok-4.7',
    label: 'Grok 4.7',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  },
  {
    id: 'grok-4.6',
    label: 'Grok 4.6',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  },
  {
    id: 'grok-4.5',
    label: 'Grok 4.5',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  }
]

export const DEFAULT_MODEL = AGENT_MODELS[0]

export function findModel(id: string) {
  return AGENT_MODELS.find((item) => item.id === id)
}
