import React, { useState, useEffect } from 'react';
import { View, StyleSheet, FlatList, Alert, Platform, PermissionsAndroid, NativeEventEmitter } from 'react-native';
import { Button, Card, Title, Paragraph, ActivityIndicator, IconButton, Checkbox, Portal, Dialog } from 'react-native-paper';
import { BleManager } from 'react-native-ble-plx';
import * as ExpoDevice from 'expo-device';
import { NativeModules } from 'react-native';

const { BluetoothAudioModule } = NativeModules;
const bluetoothEventEmitter = new NativeEventEmitter(BluetoothAudioModule);

export default function HomeScreen({ navigation }) {
  const [isScanning, setIsScanning] = useState(false);
  const [devices, setDevices] = useState([]);
  const [selectedDevices, setSelectedDevices] = useState(new Set());
  const [isConnecting, setIsConnecting] = useState(false);
  const [showConnectingDialog, setShowConnectingDialog] = useState(false);
  const [permissionsGranted, setPermissionsGranted] = useState(false);

  useEffect(() => {
    requestPermissions();
    
    // Set up event listeners for classic Bluetooth discovery
    const deviceFoundListener = bluetoothEventEmitter.addListener(
      'classicDeviceFoundDetailed',
      (device) => {
        setDevices(prevDevices => {
          const deviceExists = prevDevices.some(d => d.id === device.id);
          if (!deviceExists) {
            return [...prevDevices, device];
          }
          return prevDevices;
        });
      }
    );

    const discoveryFinishedListener = bluetoothEventEmitter.addListener(
      'classicDiscoveryFinished',
      () => {
        setIsScanning(false);
      }
    );

    return () => {
      deviceFoundListener.remove();
      discoveryFinishedListener.remove();
      if (isScanning) {
        BluetoothAudioModule.stopClassicDiscovery();
      }
    };
  }, []);

  const requestPermissions = async () => {
    if (Platform.OS === 'android') {
      try {
        if (Platform.Version >= 31) {
          const results = await Promise.all([
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN),
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT),
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION),
          ]);
          
          const allGranted = results.every(result => result === PermissionsAndroid.RESULTS.GRANTED);
          setPermissionsGranted(allGranted);
          
          if (!allGranted) {
            Alert.alert(
              'Permissions Required',
              'This app needs Bluetooth and Location permissions to scan for devices.',
              [{ text: 'OK' }]
            );
          }
        } else {
          const granted = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
          );
          setPermissionsGranted(granted === PermissionsAndroid.RESULTS.GRANTED);
        }
      } catch (error) {
        Alert.alert('Error', 'Failed to request necessary permissions.');
      }
    } else {
      setPermissionsGranted(true);
    }
  };

  const startScan = async () => {
    setDevices([]);
    setIsScanning(true);

    if (!permissionsGranted) {
      Alert.alert(
        'Permissions Required',
        'Please grant the necessary permissions to scan for Bluetooth devices.',
        [
          { text: 'Cancel', onPress: () => setIsScanning(false) },
          { text: 'Request Permissions', onPress: requestPermissions }
        ]
      );
      return;
    }

    try {
      await BluetoothAudioModule.startClassicDiscovery();
    } catch (error) {
      Alert.alert('Error', 'Failed to start Bluetooth scanning: ' + error.message);
      setIsScanning(false);
    }
  };

  const stopScan = async () => {
    try {
      await BluetoothAudioModule.cancelDiscovery();
    } catch (error) {
      Alert.alert('Error', 'Failed to stop Bluetooth scanning: ' + error.message);
    }
    setIsScanning(false);
  };

  const toggleDeviceSelection = (deviceId) => {
    setSelectedDevices(prev => {
      const newSet = new Set(prev);
      if (newSet.has(deviceId)) {
        newSet.delete(deviceId);
      } else {
        newSet.add(deviceId);
      }
      return newSet;
    });
  };

  const connectSelectedDevices = async () => {
    if (selectedDevices.size === 0) {
      Alert.alert('No Devices Selected', 'Please select at least one device to connect.');
      return;
    }

    setIsConnecting(true);
    setShowConnectingDialog(true);

    try {
      // First, ensure virtual sink is started
      const sinkStarted = await BluetoothAudioModule.startVirtualSink();
      if (!sinkStarted) {
        throw new Error('Failed to start virtual audio sink');
      }

      // Get array of selected device IDs
      const deviceIds = Array.from(selectedDevices);
      
      // Connect each device one by one to ensure proper A2DP setup
      for (const deviceId of deviceIds) {
        const device = devices.find(d => d.id === deviceId);
        if (!device) continue;

        // First create A2DP bond if not already bonded
        const isBonded = await BluetoothAudioModule.createBond(deviceId);
        if (!isBonded) {
          throw new Error(`Failed to bond with device ${device.name || deviceId}`);
        }

        // Then connect the A2DP profile
        const connected = await BluetoothAudioModule.connectDevice(deviceId);
        if (!connected) {
          throw new Error(`Failed to connect A2DP to device ${device.name || deviceId}`);
        }
      }

      // Navigate to the audio control screen
      navigation.navigate('BluetoothAudio');
    } catch (error) {
      // If there's an error, stop the virtual sink
      try {
        await BluetoothAudioModule.stopVirtualSink();
      } catch (cleanupError) {
        console.error('Failed to cleanup virtual sink:', cleanupError);
      }
      Alert.alert('Connection Error', error.message);
    } finally {
      setIsConnecting(false);
      setShowConnectingDialog(false);
    }
  };

  const renderDevice = ({ item }) => (
    <Card style={styles.deviceCard}>
      <Card.Content>
        <View style={styles.deviceHeader}>
          <View style={styles.deviceInfo}>
            <Title>{item.name || 'Unnamed Device'}</Title>
            <Paragraph>ID: {item.id.substring(0, 10)}...</Paragraph>
            <Paragraph>Signal: {item.rssi} dBm</Paragraph>
          </View>
          <Checkbox
            status={selectedDevices.has(item.id) ? 'checked' : 'unchecked'}
            onPress={() => toggleDeviceSelection(item.id)}
          />
        </View>
      </Card.Content>
    </Card>
  );

  return (
    <View style={styles.container}>
      <View style={styles.scanButtonContainer}>
        <Button 
          mode="contained" 
          icon={isScanning ? "stop" : "bluetooth"}
          onPress={isScanning ? stopScan : startScan}
          loading={isScanning}
          style={styles.scanButton}
        >
          {isScanning ? 'Stop Scan' : 'Scan for Devices'}
        </Button>
      </View>

      <FlatList
        data={devices}
        renderItem={renderDevice}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.deviceList}
        ListEmptyComponent={
          !isScanning && (
            <Card style={styles.emptyCard}>
              <Card.Content>
                <Paragraph style={styles.emptyText}>
                  No devices found. Tap the scan button to search for Bluetooth devices.
                </Paragraph>
              </Card.Content>
            </Card>
          )
        }
      />
      
      {selectedDevices.size > 0 && (
        <View style={styles.bottomContainer}>
          <Button 
            mode="contained"
            icon="hand-wave"
            onPress={connectSelectedDevices}
            style={styles.connectButton}
            loading={isConnecting}
          >
            Connect {selectedDevices.size} Device{selectedDevices.size > 1 ? 's' : ''}
          </Button>
        </View>
      )}

      <Portal>
        <Dialog visible={showConnectingDialog} dismissable={false}>
          <Dialog.Title>Connecting Devices</Dialog.Title>
          <Dialog.Content>
            <View style={styles.dialogContent}>
              <ActivityIndicator size="large" />
              <Paragraph style={styles.dialogText}>
                Establishing connections...
              </Paragraph>
            </View>
          </Dialog.Content>
        </Dialog>
      </Portal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scanButtonContainer: {
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 8,
    alignItems: 'center',
  },
  scanButton: {
    width: '80%',
    borderRadius: 25,
  },
  deviceList: {
    padding: 16,
    flexGrow: 1,
  },
  deviceCard: {
    marginBottom: 8,
  },
  deviceHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  deviceInfo: {
    flex: 1,
  },
  emptyCard: {
    marginTop: 20,
    alignItems: 'center',
  },
  emptyText: {
    textAlign: 'center',
  },
  bottomContainer: {
    padding: 16,
    backgroundColor: 'white',
    elevation: 4,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  connectButton: {
    width: '100%',
    borderRadius: 25,
  },
  dialogContent: {
    alignItems: 'center',
    padding: 16,
  },
  dialogText: {
    marginTop: 16,
    textAlign: 'center',
  },
});