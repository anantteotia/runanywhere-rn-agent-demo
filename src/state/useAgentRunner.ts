import {useState, useCallback, useRef, useEffect} from 'react';
import type {AgentState} from './types';

const MOCK_RESPONSE = [
  'Analyzing the request...\n',
  'Setting up execution environment.\n\n',
  '> Step 1: Parsing input parameters\n',
  '  - Task received successfully\n',
  '  - Validating configuration\n\n',
  '> Step 2: Running agent logic\n',
  '  - Connecting to model backend\n',
  '  - Generating response tokens\n',
  '  - Processing tool calls\n\n',
  '> Step 3: Finalizing output\n',
  '  - Aggregating results\n',
  '  - Formatting response\n\n',
  'Done. Agent completed successfully.',
];

function buildTokens(task: string, context?: string): string[] {
  const header = `Task: "${task}"\n` +
    (context ? `Context: "${context}"\n\n` : '\n');
  return [header, ...MOCK_RESPONSE];
}

export function useAgentRunner() {
  const [state, setState] = useState<AgentState>({phase: 'idle'});
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const mountedRef = useRef(true);

  const cleanup = useCallback(() => {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current);
      timerRef.current = null;
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
    setState({phase: 'idle'});
  }, [cleanup]);

  const start = useCallback(
    (task: string, context?: string) => {
      cleanup();
      const tokens = buildTokens(task, context);

      // Phase 1: downloading (progress 0→100 over ~1s)
      let progress = 0;
      setState({phase: 'downloading', progress: 0});

      timerRef.current = setInterval(() => {
        if (!mountedRef.current) { return; }
        progress += 10;
        if (progress >= 100) {
          clearInterval(timerRef.current!);
          timerRef.current = null;

          // Phase 2: loading for 500ms
          setState({phase: 'loading'});
          timerRef.current = setTimeout(() => {
            if (!mountedRef.current) { return; }
            timerRef.current = null;

            // Phase 3: running — emit tokens every 100ms
            let tokenIndex = 0;
            let accumulated = '';
            setState({phase: 'running', partialOutput: ''});

            timerRef.current = setInterval(() => {
              if (!mountedRef.current) { return; }
              if (tokenIndex < tokens.length) {
                accumulated += tokens[tokenIndex];
                tokenIndex++;
                setState({phase: 'running', partialOutput: accumulated});
              } else {
                clearInterval(timerRef.current!);
                timerRef.current = null;
                setState({phase: 'done', finalOutput: accumulated});
              }
            }, 100) as unknown as ReturnType<typeof setInterval>;
          }, 500) as unknown as ReturnType<typeof setInterval>;
        } else {
          setState({phase: 'downloading', progress});
        }
      }, 100);
    },
    [cleanup],
  );

  return {state, start, stop};
}
