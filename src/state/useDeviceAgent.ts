import {useCallback, useEffect, useRef, useState} from 'react';
import {
  isServiceEnabled,
  startAgent,
  stopAgent,
  subscribeAgentEvents,
  getAvailableModels,
  getActiveModel,
  setActiveModel,
} from '../native/agentKernel';

export type DeviceAgentState =
  | {phase: 'idle'}
  | {phase: 'running'; output: string}
  | {phase: 'done'; output: string}
  | {phase: 'error'; message: string};

// Model display names
const MODEL_LABELS: Record<string, string> = {
  'smollm2-360m-instruct-q8_0': 'SmolLM2 360M (Fast)',
  'qwen2.5-1.5b-instruct-q4_k_m': 'Qwen2.5 1.5B (Best)',
  'lfm2.5-1.2b-instruct-q4_k_m': 'LFM2.5 1.2B (Edge)',
};

export function getModelLabel(modelId: string): string {
  return MODEL_LABELS[modelId] || modelId;
}

export function useDeviceAgent() {
  const [state, setState] = useState<DeviceAgentState>({phase: 'idle'});
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [activeModel, setActiveModelState] = useState<string>('');
  const outputRef = useRef('');
  const unsubRef = useRef<null | (() => void)>(null);

  // Load available models on mount
  useEffect(() => {
    (async () => {
      try {
        const models = await getAvailableModels();
        setAvailableModels(models);
        const current = await getActiveModel();
        setActiveModelState(current);
      } catch (e) {
        console.warn('Failed to load models:', e);
      }
    })();
  }, []);

  const selectModel = useCallback(async (modelId: string) => {
    try {
      await setActiveModel(modelId);
      setActiveModelState(modelId);
      return true;
    } catch (e) {
      console.warn('Failed to set model:', e);
      return false;
    }
  }, []);

  const cleanup = useCallback(() => {
    if (unsubRef.current) {
      unsubRef.current();
      unsubRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => cleanup();
  }, [cleanup]);

  const start = useCallback(async (goal: string) => {
    cleanup();
    outputRef.current = '';

    const enabled = await isServiceEnabled();
    if (!enabled) {
      setState({phase: 'error', message: 'Enable Accessibility Service first.'});
      return;
    }

    const pushLine = (message: string) => {
      const trimmedMessage = message.trim();
      const lines = outputRef.current
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);

      if (lines.length > 0 && lines[lines.length - 1] === trimmedMessage) {
        // Skip duplicate log lines
      } else if (
        trimmedMessage.startsWith('Downloading') ||
        trimmedMessage.startsWith('Downloading model') ||
        trimmedMessage.startsWith('Model download')
      ) {
        if (
          lines.length > 0 &&
          (lines[lines.length - 1].startsWith('Downloading') ||
            lines[lines.length - 1].startsWith('Downloading model') ||
            lines[lines.length - 1].startsWith('Model download'))
        ) {
          lines[lines.length - 1] = trimmedMessage;
        } else {
          lines.push(trimmedMessage);
        }
      } else {
        lines.push(trimmedMessage);
      }

      // Keep log size reasonable
      const capped = lines.slice(-200);
      outputRef.current = `${capped.join('\n')}\n`;
    };

    unsubRef.current = subscribeAgentEvents(
      message => {
        pushLine(message);
        setState({phase: 'running', output: outputRef.current});
      },
      message => {
        if (message) {
          pushLine(message);
        }
        setState({phase: 'done', output: outputRef.current});
        cleanup();
      },
      message => {
        setState({phase: 'error', message});
        cleanup();
      },
    );

    await startAgent(goal);
  }, [cleanup]);

  const stop = useCallback(async () => {
    cleanup();
    await stopAgent();
    setState({phase: 'idle'});
  }, [cleanup]);

  return {state, start, stop, availableModels, activeModel, selectModel};
}
