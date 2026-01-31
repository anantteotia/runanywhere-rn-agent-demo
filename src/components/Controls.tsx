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
}

export function Controls({onRun, onStop, isRunning}: Props): React.JSX.Element {
  const [task, setTask] = useState('');
  const [context, setContext] = useState('');

  const canRun = !isRunning && task.trim().length > 0;

  return (
    <View style={styles.container}>
      <TextInput
        style={styles.input}
        placeholder="Describe the task..."
        placeholderTextColor="#666"
        value={task}
        onChangeText={setTask}
        editable={!isRunning}
      />
      <TextInput
        style={[styles.input, styles.contextInput]}
        placeholder="Optional context..."
        placeholderTextColor="#666"
        value={context}
        onChangeText={setContext}
        multiline
        editable={!isRunning}
      />
      <View style={styles.buttons}>
        <TouchableOpacity
          style={[styles.button, !canRun && styles.buttonDisabled]}
          onPress={() => onRun(task.trim(), context.trim() || undefined)}
          disabled={!canRun}>
          <Text style={styles.buttonText}>Run Agent</Text>
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
    padding: 12,
    gap: 10,
  },
  input: {
    backgroundColor: '#16213e',
    color: '#e0e0e0',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontSize: 14,
    borderWidth: 1,
    borderColor: '#2a3a5c',
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
    borderRadius: 8,
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  stopButton: {
    backgroundColor: '#a83279',
  },
  buttonText: {
    color: '#e0e0e0',
    fontWeight: '600',
    fontSize: 14,
  },
});
