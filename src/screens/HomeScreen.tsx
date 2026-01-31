import React, {useMemo, useState} from 'react';
import {
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {OutputPanel} from '../components/OutputPanel';
import {Controls} from '../components/Controls';
import {useAgentRunner} from '../state/useAgentRunner';
import {useDeviceAgent} from '../state/useDeviceAgent';

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
  const deviceAgent = useDeviceAgent();
  const [mode, setMode] = useState<'llm' | 'device'>('llm');

  const output = useMemo(() => {
    if (mode === 'device') {
      switch (deviceAgent.state.phase) {
        case 'running':
          return deviceAgent.state.output;
        case 'done':
          return deviceAgent.state.output;
        case 'error':
          return `Error: ${deviceAgent.state.message}`;
        default:
          return '';
      }
    }
    return getOutput(state);
  }, [mode, deviceAgent.state, state]);

  const label = useMemo(() => {
    if (mode === 'device') {
      switch (deviceAgent.state.phase) {
        case 'running':
          return {text: 'AGENT', color: '#2ecc71'};
        case 'done':
          return {text: 'DONE', color: '#3498db'};
        case 'error':
          return {text: 'ERROR', color: '#e74c3c'};
        default:
          return {text: 'READY', color: '#888'};
      }
    }
    return phaseLabel(state.phase);
  }, [mode, deviceAgent.state, state.phase]);

  const isRunning =
    mode === 'device'
      ? deviceAgent.state.phase === 'running'
      : state.phase === 'downloading' ||
        state.phase === 'loading' ||
        state.phase === 'running';

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.bg} pointerEvents="none">
        <View style={styles.blobOne} />
        <View style={styles.blobTwo} />
      </View>
      <ScrollView contentContainerStyle={styles.scrollContent} nestedScrollEnabled>
        <View style={styles.header}>
          <View>
            <Text style={styles.title}>RunAnywhere Agent</Text>
            <Text style={styles.subtitle}>On-device AI • Streaming responses</Text>
            <View style={styles.modeRow}>
              <TouchableOpacity
                style={[styles.modeButton, mode === 'llm' && styles.modeButtonActive]}
                onPress={() => setMode('llm')}>
                <Text style={styles.modeButtonText}>LLM Chat</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modeButton, mode === 'device' && styles.modeButtonActive]}
                onPress={() => setMode('device')}>
                <Text style={styles.modeButtonText}>Device Agent</Text>
              </TouchableOpacity>
            </View>
          </View>
          <View style={[styles.badge, {backgroundColor: label.color}]}
          >
            <Text style={styles.badgeText}>{label.text}</Text>
          </View>
        </View>
        <View style={styles.card}>
          <OutputPanel output={output} />
        </View>
        <View style={styles.card}>
          {mode === 'device' ? (
            <Controls
              onRun={(task) => deviceAgent.start(task)}
              onStop={deviceAgent.stop}
              isRunning={isRunning}
              modeLabel="Device Agent"
            />
          ) : (
            <Controls onRun={start} onStop={stop} isRunning={isRunning} />
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0e1224',
  },
  bg: {
    ...StyleSheet.absoluteFillObject,
  },
  blobOne: {
    position: 'absolute',
    top: -80,
    left: -40,
    width: 240,
    height: 240,
    borderRadius: 120,
    backgroundColor: '#1f6feb',
    opacity: 0.18,
  },
  blobTwo: {
    position: 'absolute',
    bottom: -120,
    right: -40,
    width: 280,
    height: 280,
    borderRadius: 140,
    backgroundColor: '#12b5cb',
    opacity: 0.16,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 18,
    paddingTop: 16,
    paddingBottom: 8,
  },
  scrollContent: {
    paddingBottom: 24,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#f4f7ff',
  },
  subtitle: {
    marginTop: 2,
    fontSize: 12,
    color: '#9fb0d6',
  },
  modeRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 10,
  },
  modeButton: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
    backgroundColor: 'rgba(18, 24, 50, 0.9)',
    borderWidth: 1,
    borderColor: 'rgba(120, 145, 200, 0.25)',
  },
  modeButtonActive: {
    backgroundColor: '#1f6feb',
    borderColor: '#1f6feb',
  },
  modeButtonText: {
    color: '#e0e0e0',
    fontSize: 11,
    fontWeight: '600',
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
  card: {
    marginHorizontal: 14,
    marginTop: 10,
    borderRadius: 14,
    backgroundColor: 'rgba(22, 28, 56, 0.9)',
    borderWidth: 1,
    borderColor: 'rgba(120, 145, 200, 0.2)',
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 8,
    shadowOffset: {width: 0, height: 4},
    elevation: 3,
  },
});
