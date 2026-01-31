import {useCallback, useEffect, useRef, useState} from 'react';
import {
  isServiceEnabled,
  startAgent,
  stopAgent,
  subscribeAgentEvents,
} from '../native/agentKernel';

export type DeviceAgentState =
  | {phase: 'idle'}
  | {phase: 'running'; output: string}
  | {phase: 'done'; output: string}
  | {phase: 'error'; message: string};

export function useDeviceAgent() {
  const [state, setState] = useState<DeviceAgentState>({phase: 'idle'});
  const outputRef = useRef('');
  const unsubRef = useRef<null | (() => void)>(null);

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
      const lines = outputRef.current
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);

      if (message.startsWith('Downloading model')) {
        if (lines.length > 0 && lines[lines.length - 1].startsWith('Downloading model')) {
          lines[lines.length - 1] = message;
        } else {
          lines.push(message);
        }
      } else {
        lines.push(message);
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

  return {state, start, stop};
}
