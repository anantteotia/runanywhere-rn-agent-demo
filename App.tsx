import React from 'react';
import {StatusBar} from 'react-native';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {HomeScreen} from './src/screens/HomeScreen';

function App(): React.JSX.Element {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#1a1a2e" />
      <HomeScreen />
    </SafeAreaProvider>
  );
}

export default App;
