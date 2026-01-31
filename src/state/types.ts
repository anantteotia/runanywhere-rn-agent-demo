export type AgentStatus = 'idle' | 'running' | 'error';

export interface AgentMessage {
  id: string;
  type: 'text' | 'tool_use' | 'error' | 'done';
  content: string;
  timestamp: number;
}

export interface RunAnywhereConfig {
  apiKey: string;
  endpoint: string;
  model?: string;
  timeout?: number;
}
