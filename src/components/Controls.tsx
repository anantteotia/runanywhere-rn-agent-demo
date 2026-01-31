import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';

export function Controls(): React.JSX.Element {
  return (
    <View style={styles.container}>
      <TouchableOpacity style={styles.button}>
        <Text style={styles.buttonText}>Start Agent</Text>
      </TouchableOpacity>
      <TouchableOpacity style={[styles.button, styles.stopButton]}>
        <Text style={styles.buttonText}>Stop</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 12,
    padding: 16,
  },
  button: {
    backgroundColor: '#0f3460',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
  },
  stopButton: {
    backgroundColor: '#533483',
  },
  buttonText: {
    color: '#e0e0e0',
    fontWeight: '600',
  },
});
