/** Models this agent can configure on the public xAI OpenAI-compatible API. */
export const AGENT_MODELS = [
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

export function findModel(id) {
  return AGENT_MODELS.find((item) => item.id === id) || null
}
