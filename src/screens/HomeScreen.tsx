import React from 'react';
import {SafeAreaView, StyleSheet, Text, View} from 'react-native';
import {OutputPanel} from '../components/OutputPanel';
import {Controls} from '../components/Controls';
import {useAgentRunner} from '../state/useAgentRunner';

function getOutput(state: ReturnType<typeof useAgentRunner>['state']): string {
  switch (state.phase) {
    case 'downloading':
      return `Downloading model... ${state.progress}%`;
    case 'loading':
      return 'Loading model...';
    case 'running':
      return state.partialOutput;
    case 'done':
      return state.finalOutput;
    case 'error':
      return `Error: ${state.message}`;
    default:
      return '';
  }
}

function phaseLabel(phase: string): {text: string; color: string} {
  switch (phase) {
    case 'downloading':
      return {text: 'DOWNLOADING', color: '#f39c12'};
    case 'loading':
      return {text: 'LOADING', color: '#f39c12'};
    case 'running':
      return {text: 'RUNNING', color: '#2ecc71'};
    case 'done':
      return {text: 'DONE', color: '#3498db'};
    case 'error':
      return {text: 'ERROR', color: '#e74c3c'};
    default:
      return {text: 'IDLE', color: '#888'};
  }
}

export function HomeScreen(): React.JSX.Element {
  const {state, start, stop} = useAgentRunner();
  const output = getOutput(state);
  const label = phaseLabel(state.phase);
  const isRunning =
    state.phase === 'downloading' ||
    state.phase === 'loading' ||
    state.phase === 'running';

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>RunAnywhere Agent</Text>
        <View style={[styles.badge, {backgroundColor: label.color}]}>
          <Text style={styles.badgeText}>{label.text}</Text>
        </View>
      </View>
      <OutputPanel output={output} />
      <Controls onRun={start} onStop={stop} isRunning={isRunning} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    paddingVertical: 14,
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#e0e0e0',
  },
  badge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
  },
  badgeText: {
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
});
