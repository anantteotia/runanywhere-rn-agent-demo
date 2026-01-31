export type AgentState =
  | {phase: 'idle'}
  | {phase: 'downloading'; progress: number}
  | {phase: 'loading'}
  | {phase: 'running'; partialOutput: string}
  | {phase: 'done'; finalOutput: string}
  | {phase: 'error'; message: string};

export type AgentEvent =
  | {type: 'download_progress'; progress: number}
  | {type: 'token'; text: string}
  | {type: 'done'}
  | {type: 'error'; message: string};

export interface RunAnywhereConfig {
  apiKey: string;
  endpoint: string;
  model?: string;
  timeout?: number;
}
