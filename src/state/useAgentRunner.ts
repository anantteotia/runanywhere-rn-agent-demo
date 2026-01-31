import {useState, useCallback} from 'react';
import type {AgentStatus} from './types';

export function useAgentRunner() {
  const [status, setStatus] = useState<AgentStatus>('idle');
  const [output, setOutput] = useState<string[]>([]);

  const start = useCallback(() => {
    setStatus('running');
    setOutput([]);
  }, []);

  const stop = useCallback(() => {
    setStatus('idle');
  }, []);

  return {status, output, start, stop};
}
