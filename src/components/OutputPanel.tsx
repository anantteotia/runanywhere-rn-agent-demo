import React from 'react';
import {ScrollView, StyleSheet, Text} from 'react-native';

export function OutputPanel(): React.JSX.Element {
  return (
    <ScrollView style={styles.container}>
      <Text style={styles.placeholder}>Agent output will appear here.</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: '#16213e',
    margin: 12,
    borderRadius: 8,
  },
  placeholder: {
    color: '#888',
    fontFamily: 'monospace',
  },
});
