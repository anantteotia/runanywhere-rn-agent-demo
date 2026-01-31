import {NativeModules} from 'react-native';

const {RunAnywhere} = NativeModules;

export function initialize(apiKey: string, endpoint: string): Promise<void> {
  return RunAnywhere?.initialize(apiKey, endpoint);
}

export function startAgent(prompt: string): Promise<string> {
  return RunAnywhere?.startAgent(prompt);
}

export function stopAgent(): Promise<void> {
  return RunAnywhere?.stopAgent();
}

export default {initialize, startAgent, stopAgent};
