import React, { useState, useEffect } from 'react';
import { View, StyleSheet, FlatList, Alert, Platform, PermissionsAndroid } from 'react-native';
import { Button, Card, Title, Paragraph, ActivityIndicator, IconButton } from 'react-native-paper';
import { BleManager } from 'react-native-ble-plx';
import * as ExpoDevice from 'expo-device';

// Initialize BLE manager with error handling
let bleManager = null;
try {
  if (Platform.OS !== 'web') {
    bleManager = new BleManager();
  }
} catch (error) {
  console.error('Failed to initialize BLE manager:', error);
}

// Mock data as fallback
const mockDevices = [
  { id: '1', name: 'Speaker XB-33', rssi: -65 },
  { id: '2', name: 'Headphones WH-1000', rssi: -72 },
  { id: '3', name: 'Car Audio System', rssi: -80 },
];

export default function HomeScreen({ navigation }) {
  const [isScanning, setIsScanning] = useState(false);
  const [devices, setDevices] = useState([]);
  const [useMockData, setUseMockData] = useState(false); // Default to real data now
  const [permissionsGranted, setPermissionsGranted] = useState(false);
  const [scanTimeout, setScanTimeout] = useState(null); // Add state for tracking timeout

  // Request permissions on component mount
  useEffect(() => {
    requestPermissions();

    // Clean up BLE manager and any pending timeouts on unmount
    return () => {
      if (bleManager) {
        try {
          stopScan();
          bleManager.destroy();
        } catch (error) {
          console.error('Error cleaning up BLE manager:', error);
        }
      }
    };
  }, []);

  // Request necessary permissions for Bluetooth
  const requestPermissions = async () => {
    if (Platform.OS === 'android') {
      try {
        // For Android 12+, we need BLUETOOTH_SCAN and BLUETOOTH_CONNECT
        if (Platform.Version >= 31) { // Android 12 (API level 31)
          const results = await Promise.all([
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, {
              title: 'Bluetooth Scan Permission',
              message: 'This app needs to scan for Bluetooth devices.',
              buttonPositive: 'OK',
            }),
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT, {
              title: 'Bluetooth Connect Permission',
              message: 'This app needs to connect to Bluetooth devices.',
              buttonPositive: 'OK',
            }),
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION, {
              title: 'Location Permission',
              message: 'This app needs access to your location for Bluetooth scanning.',
              buttonPositive: 'OK',
            }),
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
          // For older Android versions
          const granted = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            {
              title: 'Location Permission',
              message: 'This app needs access to your location for Bluetooth scanning.',
              buttonPositive: 'OK',
            },
          );
          
          setPermissionsGranted(granted === PermissionsAndroid.RESULTS.GRANTED);
          
          if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
            Alert.alert(
              'Permission Required',
              'This app needs Location permission to scan for Bluetooth devices.',
              [{ text: 'OK' }]
            );
          }
        }
      } catch (error) {
        console.error('Error requesting permissions:', error);
        Alert.alert('Error', 'Failed to request necessary permissions.');
      }
    } else {
      // iOS doesn't need explicit permissions for basic scanning
      setPermissionsGranted(true);
    }
  };

  const startScan = () => {
    // Clear previous devices and any existing timeout
    setDevices([]);
    if (scanTimeout) {
      clearTimeout(scanTimeout);
    }
    setIsScanning(true);

    if (useMockData) {
      // Simulate scanning delay with mock data
      const timeout = setTimeout(() => {
        setDevices(mockDevices);
        setIsScanning(false);
      }, 2000);
      setScanTimeout(timeout);
      return;
    }

    // Check if BLE manager is available
    if (!bleManager) {
      Alert.alert(
        'Bluetooth Not Available',
        'Bluetooth functionality is not available on this device or platform.',
        [{ text: 'Use Mock Data', onPress: () => {
          setUseMockData(true);
          startScan(); // Restart scan with mock data
        }}]
      );
      setIsScanning(false);
      return;
    }

    // Check permissions
    if (!permissionsGranted) {
      Alert.alert(
        'Permissions Required',
        'Please grant the necessary permissions to scan for Bluetooth devices.',
        [
          { text: 'Cancel', onPress: () => setIsScanning(false) },
          { text: 'Request Permissions', onPress: async () => {
            await requestPermissions();
            setIsScanning(false);
          }}
        ]
      );
      return;
    }

    // Start real Bluetooth scanning
    try {
      console.log('Starting BLE scan...');
      
      bleManager.startDeviceScan(null, null, (error, device) => {
        if (error) {
          console.error('Scanning error:', error);
          Alert.alert('Scanning Error', error.message);
          stopScan();
          return;
        }

        if (device) {
          console.log('Found device:', device.name || 'Unnamed', device.id);
          
          // Add device to the list if it has a name or if we want to show all devices
          if (device.name) {
            setDevices(prevDevices => {
              // Check if device already exists in the list
              const deviceExists = prevDevices.some(d => d.id === device.id);
              if (!deviceExists) {
                return [...prevDevices, device];
              }
              return prevDevices;
            });
          }
        }
      });

      // Stop scanning after 15 seconds
      const timeout = setTimeout(() => {
        stopScan();
      }, 15000);
      setScanTimeout(timeout);
      
    } catch (error) {
      console.error('Error starting scan:', error);
      Alert.alert('Error', 'Failed to start Bluetooth scanning: ' + error.message);
      setIsScanning(false);
    }
  };

  const stopScan = () => {
    // Clear any existing timeout
    if (scanTimeout) {
      clearTimeout(scanTimeout);
      setScanTimeout(null);
    }

    // Stop the BLE scan if it's running
    if (bleManager && isScanning) {
      try {
        bleManager.stopDeviceScan();
        console.log('BLE scan stopped');
      } catch (error) {
        console.error('Error stopping scan:', error);
        Alert.alert('Error', 'Failed to stop Bluetooth scanning: ' + error.message);
      }
    }

    // Update scanning state
    setIsScanning(false);
  };

  const renderDevice = ({ item }) => (
    <Card style={styles.deviceCard} onPress={() => navigation.navigate('Device', { deviceId: item.id, deviceName: item.name || 'Unnamed Device' })}>
      <Card.Content>
        <View style={styles.deviceHeader}>
          <Title>{item.name || 'Unnamed Device'}</Title>
          <IconButton icon="bluetooth" size={24} color="#2196F3" />
        </View>
        <Paragraph>ID: {typeof item.id === 'string' ? (item.id.length > 10 ? item.id.substring(0, 10) + '...' : item.id) : item.id}</Paragraph>
        <Paragraph>Signal Strength: {item.rssi} dBm</Paragraph>
      </Card.Content>
    </Card>
  );

  return (
    <View style={styles.container}>
      {/* Scan button at the top center */}
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
      
      {isScanning && (
        <View style={styles.scanningOverlay}>
          <ActivityIndicator size="large" color="#2196F3" />
          <Title style={styles.scanningText}>Scanning for devices...</Title>
        </View>
      )}

      {/* Toggle between mock and real data (for development) */}
      <View style={styles.devModeContainer}>
        <Button 
          mode="text" 
          onPress={() => setUseMockData(!useMockData)}
          style={styles.devModeButton}
        >
          {useMockData ? "Using Mock Data" : "Using Real BLE"}
        </Button>
      </View>

      {/* Settings button at the bottom */}
      <View style={styles.buttonContainer}>
        <Button 
          mode="outlined" 
          onPress={() => navigation.navigate('Settings')}
          style={styles.settingsButton}
          icon="cog"
        >
          Settings
        </Button>
      </View>
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
  scanningOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(255, 255, 255, 0.8)',
    padding: 16,
    alignItems: 'center',
  },
  scanningText: {
    marginTop: 8,
  },
  buttonContainer: {
    padding: 16,
    alignItems: 'center',
  },
  settingsButton: {
    width: '80%',
    borderRadius: 25,
  },
  emptyCard: {
    marginTop: 20,
    alignItems: 'center',
  },
  emptyText: {
    textAlign: 'center',
  },
  devModeContainer: {
    alignItems: 'center',
    paddingBottom: 8,
  },
  devModeButton: {
    marginVertical: 0,
  },
}); 