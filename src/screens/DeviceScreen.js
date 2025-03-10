import React, { useState } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Card, Title, Paragraph, Button, Switch, Divider, List } from 'react-native-paper';

export default function DeviceScreen({ route, navigation }) {
  const { deviceId, deviceName } = route.params;
  const [isConnected, setIsConnected] = useState(false);
  const [audioProfile, setAudioProfile] = useState('A2DP');

  // Mock device details
  const deviceDetails = {
    address: '00:11:22:33:44:55',
    type: 'Audio',
    services: ['A2DP', 'AVRCP', 'HFP'],
    batteryLevel: '85%',
  };

  const toggleConnection = () => {
    setIsConnected(!isConnected);
  };

  return (
    <ScrollView style={styles.container}>
      <Card style={styles.card}>
        <Card.Content>
          <Title style={styles.title}>{deviceName}</Title>
          <Paragraph>ID: {deviceId}</Paragraph>
          <Paragraph>Address: {deviceDetails.address}</Paragraph>
          <Paragraph>Type: {deviceDetails.type}</Paragraph>
          <Paragraph>Battery: {deviceDetails.batteryLevel}</Paragraph>
          
          <View style={styles.connectionContainer}>
            <Paragraph>Connection Status:</Paragraph>
            <Switch
              value={isConnected}
              onValueChange={toggleConnection}
              color="#2196F3"
            />
            <Paragraph>{isConnected ? 'Connected' : 'Disconnected'}</Paragraph>
          </View>
        </Card.Content>
      </Card>

      <Card style={styles.card}>
        <Card.Content>
          <Title>Audio Profiles</Title>
          <Divider style={styles.divider} />
          
          <List.Item
            title="A2DP (Advanced Audio)"
            description="High-quality audio streaming"
            left={props => <List.Icon {...props} icon="music" />}
            right={props => (
              <Switch
                value={audioProfile === 'A2DP'}
                onValueChange={() => setAudioProfile('A2DP')}
                color="#2196F3"
              />
            )}
          />
          
          <List.Item
            title="HFP (Hands-Free)"
            description="For calls and voice commands"
            left={props => <List.Icon {...props} icon="phone" />}
            right={props => (
              <Switch
                value={audioProfile === 'HFP'}
                onValueChange={() => setAudioProfile('HFP')}
                color="#2196F3"
              />
            )}
          />
        </Card.Content>
      </Card>

      <View style={styles.buttonContainer}>
        <Button 
          mode="contained" 
          onPress={() => navigation.goBack()}
          style={styles.button}
        >
          Back to Devices
        </Button>
        
        <Button 
          mode="outlined" 
          onPress={() => {}}
          style={styles.button}
          disabled={!isConnected}
        >
          Disconnect
        </Button>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
    padding: 16,
  },
  card: {
    marginBottom: 16,
  },
  title: {
    fontSize: 24,
    marginBottom: 8,
  },
  connectionContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 16,
  },
  divider: {
    marginVertical: 8,
  },
  buttonContainer: {
    marginTop: 8,
    marginBottom: 24,
  },
  button: {
    marginVertical: 8,
  },
}); 