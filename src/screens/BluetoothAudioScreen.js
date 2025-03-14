import React, { useState, useEffect } from 'react';
import { View, StyleSheet, Alert } from 'react-native';
import { Button, Card, Title, Paragraph, Switch, IconButton } from 'react-native-paper';
import { NativeEventEmitter, NativeModules } from 'react-native';

const { BluetoothAudioModule } = NativeModules;
const eventEmitter = new NativeEventEmitter(BluetoothAudioModule);

export default function BluetoothAudioScreen({ navigation }) {
  const [isVirtualSinkActive, setIsVirtualSinkActive] = useState(false);
  const [isAudioCapturing, setIsAudioCapturing] = useState(false);
  const [connectedDevices, setConnectedDevices] = useState([]);

  useEffect(() => {
    const deviceConnectedSubscription = eventEmitter.addListener(
      'deviceConnected',
      (deviceAddress) => {
        setConnectedDevices(prev => [...prev, deviceAddress]);
      }
    );

    const deviceDisconnectedSubscription = eventEmitter.addListener(
      'deviceDisconnected',
      (deviceAddress) => {
        setConnectedDevices(prev => prev.filter(addr => addr !== deviceAddress));
      }
    );

    return () => {
      deviceConnectedSubscription.remove();
      deviceDisconnectedSubscription.remove();
    };
  }, []);

  const toggleAudioCapture = async () => {
    try {
      if (!isAudioCapturing) {
        await BluetoothAudioModule.startAudioCapture();
        setIsAudioCapturing(true);
        Alert.alert('Success', 'Audio capture started');
      } else {
        await BluetoothAudioModule.stopAudioCapture();
        setIsAudioCapturing(false);
        Alert.alert('Success', 'Audio capture stopped');
      }
    } catch (error) {
      Alert.alert('Error', error.message);
    }
  };

  const disconnectDevice = async (deviceAddress) => {
    try {
      await BluetoothAudioModule.disconnectDevice(deviceAddress);
      Alert.alert('Success', 'Device disconnected successfully');
    } catch (error) {
      Alert.alert('Error', 'Failed to disconnect device: ' + error.message);
    }
  };

  return (
    <View style={styles.container}>
      <Card style={styles.card}>
        <Card.Content>
          <Title>Audio Control</Title>
          <View style={styles.switchContainer}>
            <Paragraph>Audio Capture</Paragraph>
            <Switch
              value={isAudioCapturing}
              onValueChange={toggleAudioCapture}
            />
          </View>
          <Paragraph style={styles.hint}>
            Toggle this switch when you're ready to play audio to connected devices
          </Paragraph>
        </Card.Content>
      </Card>

      <Card style={styles.card}>
        <Card.Content>
          <Title>Connected Devices</Title>
          {connectedDevices.map((deviceAddress) => (
            <View key={deviceAddress} style={styles.deviceContainer}>
              <Paragraph>{deviceAddress}</Paragraph>
              <Button
                mode="outlined"
                onPress={() => disconnectDevice(deviceAddress)}
              >
                Disconnect
              </Button>
            </View>
          ))}
        </Card.Content>
      </Card>

      <Button
        mode="contained"
        onPress={() => navigation.navigate('Home')}
        style={styles.button}
      >
        Scan for Devices
      </Button>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: '#f5f5f5',
  },
  card: {
    marginBottom: 16,
  },
  switchContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 8,
  },
  deviceContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 8,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  button: {
    marginTop: 16,
  },
  hint: {
    marginTop: 8,
    fontStyle: 'italic',
    color: '#666',
  },
}); 