import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
} from 'react-native';

export default function App() {
  const [room, setRoom] = useState('');
  const [msg, setMsg] = useState('');
  const ready = room.trim().length >= 6;

  const onConnect = () => {
    setMsg('Room code save ho gaya. Overlay aur chat agle step mein judenge.');
  };

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0B1738" />
      <Text style={styles.title}>Secret Chat</Text>
      <Text style={styles.sub}>Dono phone mein same room code daalo</Text>

      <TextInput
        style={styles.input}
        value={room}
        onChangeText={setRoom}
        placeholder="Room code (kam se kam 6 akshar)"
        placeholderTextColor="#6B7AA8"
        autoCapitalize="none"
        autoCorrect={false}
      />

      <TouchableOpacity
        style={[styles.btn, !ready && styles.btnOff]}
        disabled={!ready}
        onPress={onConnect}
      >
        <Text style={styles.btnText}>Connect</Text>
      </TouchableOpacity>

      {msg ? <Text style={styles.msg}>{msg}</Text> : null}

      <Text style={styles.foot}>Version 1.0.0 (base build)</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0B1738',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  title: { color: '#FFFFFF', fontSize: 34, fontWeight: '800' },
  sub: { color: '#9FB0E0', fontSize: 15, marginTop: 8, marginBottom: 28 },
  input: {
    width: '100%',
    borderWidth: 1,
    borderColor: '#2B3E7A',
    backgroundColor: '#101F4D',
    color: '#FFFFFF',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 14,
    fontSize: 17,
  },
  btn: {
    width: '100%',
    backgroundColor: '#4F8CFF',
    borderRadius: 14,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: 16,
  },
  btnOff: { backgroundColor: '#2B3E7A' },
  btnText: { color: '#FFFFFF', fontSize: 17, fontWeight: '700' },
  msg: { color: '#FF6FAE', marginTop: 18, textAlign: 'center', fontSize: 14 },
  foot: { position: 'absolute', bottom: 24, color: '#51639C', fontSize: 12 },
});
