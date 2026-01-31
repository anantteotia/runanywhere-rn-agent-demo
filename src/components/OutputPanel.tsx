import React, {useEffect, useRef} from 'react';
import {ScrollView, StyleSheet, Text} from 'react-native';

interface Props {
  output: string;
}

export function OutputPanel({output}: Props): React.JSX.Element {
  const scrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    if (output) {
      scrollRef.current?.scrollToEnd({animated: true});
    }
  }, [output]);

  return (
    <ScrollView ref={scrollRef} style={styles.container}>
      <Text style={styles.text}>
        {output || 'Agent output will appear here.'}
      </Text>
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
  text: {
    color: '#c8d6e5',
    fontFamily: 'monospace',
    fontSize: 13,
    lineHeight: 20,
  },
});
