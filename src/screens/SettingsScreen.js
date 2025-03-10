import React, { useState } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { List, Switch, Divider, Button, Card, Title } from 'react-native-paper';

export default function SettingsScreen({ navigation }) {
  const [autoConnect, setAutoConnect] = useState(true);
  const [showAllDevices, setShowAllDevices] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [notifications, setNotifications] = useState(true);

  return (
    <ScrollView style={styles.container}>
      <Card style={styles.card}>
        <Card.Content>
          <Title>Bluetooth Settings</Title>
          <Divider style={styles.divider} />
          
          <List.Item
            title="Auto-connect to known devices"
            description="Automatically connect to previously paired devices"
            right={props => (
              <Switch
                value={autoConnect}
                onValueChange={() => setAutoConnect(!autoConnect)}
                color="#2196F3"
              />
            )}
          />
          
          <List.Item
            title="Show all devices"
            description="Display devices without names"
            right={props => (
              <Switch
                value={showAllDevices}
                onValueChange={() => setShowAllDevices(!showAllDevices)}
                color="#2196F3"
              />
            )}
          />
        </Card.Content>
      </Card>

      <Card style={styles.card}>
        <Card.Content>
          <Title>App Settings</Title>
          <Divider style={styles.divider} />
          
          <List.Item
            title="Dark Mode"
            description="Use dark theme throughout the app"
            right={props => (
              <Switch
                value={darkMode}
                onValueChange={() => setDarkMode(!darkMode)}
                color="#2196F3"
              />
            )}
          />
          
          <List.Item
            title="Notifications"
            description="Receive alerts about device connections"
            right={props => (
              <Switch
                value={notifications}
                onValueChange={() => setNotifications(!notifications)}
                color="#2196F3"
              />
            )}
          />
        </Card.Content>
      </Card>

      <View style={styles.buttonContainer}>
        <Button 
          mode="contained" 
          onPress={() => navigation.navigate('Home')}
          style={styles.button}
        >
          Save Settings
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