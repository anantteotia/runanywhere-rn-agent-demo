import {NativeModules, NativeEventEmitter} from 'react-native';
import type {AgentEvent} from '../state/types';

const {RunAnywhere} = NativeModules;
const emitter = new NativeEventEmitter(RunAnywhere);

export const Events = {
  DOWNLOAD_PROGRESS: 'RUNANYWHERE_DOWNLOAD_PROGRESS',
  TOKEN: 'RUNANYWHERE_TOKEN',
  DONE: 'RUNANYWHERE_DONE',
  ERROR: 'RUNANYWHERE_ERROR',
} as const;

export function initialize(apiKey: string, endpoint: string): Promise<void> {
  return RunAnywhere.initialize(apiKey, endpoint);
}

export function downloadModel(modelName: string): Promise<void> {
  return RunAnywhere.downloadModel(modelName);
}

export function loadModel(modelName: string): Promise<void> {
  return RunAnywhere.loadModel(modelName);
}

export function runAgent(task: string, context?: string): Promise<void> {
  return RunAnywhere.runAgent(task, context ?? null);
}

export function cancelRun(): Promise<void> {
  return RunAnywhere.cancelRun();
}

export type EventCleanup = () => void;

export function subscribe(callback: (event: AgentEvent) => void): EventCleanup {
  const subs = [
    emitter.addListener(Events.DOWNLOAD_PROGRESS, (data: {progress: number}) => {
      callback({type: 'download_progress', progress: data.progress});
    }),
    emitter.addListener(Events.TOKEN, (data: {token: string}) => {
      callback({type: 'token', text: data.token});
    }),
    emitter.addListener(Events.DONE, () => {
      callback({type: 'done'});
    }),
    emitter.addListener(Events.ERROR, (data: {message: string}) => {
      callback({type: 'error', message: data.message});
    }),
  ];

  return () => subs.forEach(s => s.remove());
}
