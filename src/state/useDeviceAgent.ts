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

    unsubRef.current = subscribeAgentEvents(
      message => {
        outputRef.current += `${message}\n`;
        setState({phase: 'running', output: outputRef.current});
      },
      message => {
        if (message) {
          outputRef.current += `${message}\n`;
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
