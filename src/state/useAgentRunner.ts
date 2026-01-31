import {useState, useCallback, useRef, useEffect} from 'react';
import type {AgentEvent, AgentState} from './types';
import {
  cancelRun,
  downloadModel,
  initialize,
  loadModel,
  runAgent,
  subscribe,
} from '../native/runanywhere';

const MODEL_ID = 'smollm2-360m-instruct-q8_0';
const API_KEY = '';
const ENDPOINT = '';

function getTodayString() {
  return new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}

export function useAgentRunner() {
  const [state, setState] = useState<AgentState>({phase: 'idle'});
  const mountedRef = useRef(true);
  const outputRef = useRef('');
  const unsubscribeRef = useRef<(() => void) | null>(null);
  const modelReadyRef = useRef(false);

  const cleanup = useCallback(() => {
    if (unsubscribeRef.current) {
      unsubscribeRef.current();
      unsubscribeRef.current = null;
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      cleanup();
    };
  }, [cleanup]);

  const stop = useCallback(() => {
    cleanup();
    cancelRun().catch(() => null);
    setState({phase: 'idle'});
  }, [cleanup]);

  const handleEvent = useCallback((event: AgentEvent) => {
    if (!mountedRef.current) {
      return;
    }

    switch (event.type) {
      case 'download_progress':
        setState({phase: 'downloading', progress: event.progress});
        break;
      case 'token':
        outputRef.current += event.text;
        setState({phase: 'running', partialOutput: outputRef.current});
        break;
      case 'done':
        setState({phase: 'done', finalOutput: outputRef.current});
        cleanup();
        break;
      case 'error':
        setState({phase: 'error', message: event.message});
        cleanup();
        break;
      default:
        break;
    }
  }, [cleanup]);

  const start = useCallback(async (task: string, context?: string) => {
    cleanup();
    outputRef.current = '';
    setState({phase: 'downloading', progress: 0});
    unsubscribeRef.current = subscribe(handleEvent);

    try {
      await initialize(API_KEY, ENDPOINT);

      if (!modelReadyRef.current) {
        await downloadModel(MODEL_ID);
        if (!mountedRef.current) {
          return;
        }
        setState({phase: 'loading'});
        await loadModel(MODEL_ID);
        modelReadyRef.current = true;
      } else {
        setState({phase: 'loading'});
      }

      const today = getTodayString();
      const dateContext = `Today's date is ${today}.`;
      const finalContext = context
        ? `${context}\n\n${dateContext}`
        : dateContext;

      await runAgent(task, finalContext);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unknown error';
      setState({phase: 'error', message});
      cleanup();
    }
  }, [cleanup, handleEvent]);

  return {state, start, stop};
}
