import React from 'react';
import {SafeAreaView, StyleSheet, Text} from 'react-native';
import {OutputPanel} from '../components/OutputPanel';
import {Controls} from '../components/Controls';

export function HomeScreen(): React.JSX.Element {
  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>RunAnywhere Agent Demo</Text>
      <OutputPanel />
      <Controls />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#e0e0e0',
    textAlign: 'center',
    paddingVertical: 16,
  },
});
