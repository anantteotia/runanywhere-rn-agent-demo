import React, {useState} from 'react';
import {
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

interface Props {
  onRun: (task: string, context?: string) => void;
  onStop: () => void;
  isRunning: boolean;
  modeLabel?: string;
}

export function Controls({
  onRun,
  onStop,
  isRunning,
  modeLabel,
}: Props): React.JSX.Element {
  const [task, setTask] = useState('');
  const [context, setContext] = useState('');

  const canRun = !isRunning && task.trim().length > 0;
  const buttonLabel = modeLabel ? `Run ${modeLabel}` : 'Run Agent';

  return (
    <View style={styles.container}>
      <Text style={styles.label}>Task</Text>
      <TextInput
        style={styles.input}
        placeholder="Describe the task..."
        placeholderTextColor="#7e8bb3"
        value={task}
        onChangeText={setTask}
        editable={!isRunning}
      />
      <Text style={styles.label}>Context (optional)</Text>
      <TextInput
        style={[styles.input, styles.contextInput]}
        placeholder="Optional context..."
        placeholderTextColor="#7e8bb3"
        value={context}
        onChangeText={setContext}
        multiline
        editable={!isRunning}
      />
      <View style={styles.buttons}>
        <TouchableOpacity
          style={[styles.button, styles.primary, !canRun && styles.buttonDisabled]}
          onPress={() => onRun(task.trim(), context.trim() || undefined)}
          disabled={!canRun}>
          <Text style={styles.buttonText}>{buttonLabel}</Text>
        </TouchableOpacity>
        {isRunning && (
          <TouchableOpacity
            style={[styles.button, styles.stopButton]}
            onPress={onStop}>
            <Text style={styles.buttonText}>Stop</Text>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 14,
    gap: 10,
  },
  label: {
    color: '#a8b6de',
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.3,
  },
  input: {
    backgroundColor: 'rgba(18, 24, 50, 0.9)',
    color: '#f4f7ff',
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontSize: 14,
    borderWidth: 1,
    borderColor: 'rgba(120, 145, 200, 0.25)',
  },
  contextInput: {
    minHeight: 60,
    textAlignVertical: 'top',
  },
  buttons: {
    flexDirection: 'row',
    gap: 12,
  },
  button: {
    backgroundColor: '#0f3460',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 10,
  },
  primary: {
    backgroundColor: '#1f6feb',
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  stopButton: {
    backgroundColor: '#d14b63',
  },
  buttonText: {
    color: '#e0e0e0',
    fontWeight: '600',
    fontSize: 14,
  },
});
