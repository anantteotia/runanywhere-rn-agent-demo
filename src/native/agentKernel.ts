import {NativeEventEmitter, NativeModules} from 'react-native';

const {AgentKernel} = NativeModules;

const emitter = new NativeEventEmitter(AgentKernel);

export function isServiceEnabled(): Promise<boolean> {
  return AgentKernel.isServiceEnabled();
}

export function startAgent(goal: string): Promise<void> {
  return AgentKernel.startAgent(goal);
}

export function stopAgent(): Promise<void> {
  return AgentKernel.stopAgent();
}

export function getAvailableModels(): Promise<string[]> {
  return AgentKernel.getAvailableModels();
}

export function getActiveModel(): Promise<string> {
  return AgentKernel.getActiveModel();
}

export function setActiveModel(modelId: string): Promise<boolean> {
  return AgentKernel.setActiveModel(modelId);
}

export function subscribeAgentEvents(
  onLog: (message: string) => void,
  onDone: (message?: string) => void,
  onError: (message: string) => void,
) {
  const logSub = emitter.addListener('AGENT_LOG', event =>
    onLog(event?.message ?? ''),
  );
  const doneSub = emitter.addListener('AGENT_DONE', event =>
    onDone(event?.message),
  );
  const errorSub = emitter.addListener('AGENT_ERROR', event =>
    onError(event?.message ?? 'Unknown error'),
  );

  return () => {
    logSub.remove();
    doneSub.remove();
    errorSub.remove();
  };
}
