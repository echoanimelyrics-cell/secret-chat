import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  AppState,
  PermissionsAndroid,
  Platform,
  ScrollView,
} from 'react-native';
import OverlayChat from './modules/overlay-chat';
import { DB_URL } from './config';

const ROOM_RE = /^[a-z0-9]{6,32}$/;

function Row({ ok, label }) {
  return (
    <View style={styles.row}>
      <Text style={[styles.dot, ok ? styles.dotOk : styles.dotNo]}>{ok ? 'OK' : 'X'}</Text>
      <Text style={styles.rowText}>{label}</Text>
    </View>
  );
}

export default function App() {
  const [room, setRoom] = useState('');
  const [overlayOk, setOverlayOk] = useState(false);
  const [running, setRunning] = useState(false);
  const [msg, setMsg] = useState('');

  const refresh = useCallback(() => {
    try {
      setOverlayOk(!!OverlayChat.canDrawOverlays());
      setRunning(!!OverlayChat.isRunning());
    } catch (e) {
      setMsg('Native module load nahi hua: ' + String(e && e.message));
    }
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') refresh();
    });
    const timer = setInterval(refresh, 1500);
    return () => {
      sub.remove();
      clearInterval(timer);
    };
  }, [refresh]);

  const code = room.trim().toLowerCase();
  const codeOk = ROOM_RE.test(code);
  const dbOk = DB_URL.startsWith('https://');

  const askNotif = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      try {
        await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
      } catch (e) {}
    }
  };

  const onOverlayPerm = () => {
    try {
      OverlayChat.openOverlaySettings();
    } catch (e) {
      setMsg('Settings nahi khuli: ' + String(e && e.message));
    }
  };

  const onStart = async () => {
    if (!dbOk) {
      setMsg('Firebase URL abhi config.js mein nahi daala gaya.');
      return;
    }
    if (!codeOk) {
      setMsg('Room code 6 se 32 akshar ka ho (sirf a-z aur 0-9).');
      return;
    }
    if (!overlayOk) {
      setMsg('Pehle Display over other apps permission do.');
      return;
    }
    await askNotif();
    try {
      OverlayChat.start(DB_URL, code);
      setMsg('Sharing ON. Ab kisi bhi app par jao, kinare par Aa button dikhega.');
    } catch (e) {
      setMsg('Start nahi hua: ' + String(e && e.message));
    }
    setTimeout(refresh, 600);
  };

  const onStop = () => {
    try {
      OverlayChat.stop();
    } catch (e) {}
    setMsg('Sharing band.');
    setTimeout(refresh, 400);
  };

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <StatusBar barStyle="light-content" backgroundColor="#0B1738" />
      <Text style={styles.title}>Secret Chat</Text>
      <Text style={styles.sub}>Dono phone mein same room code daalo</Text>

      <TextInput
        style={styles.input}
        value={room}
        onChangeText={setRoom}
        placeholder="Room code (6 se 32 akshar, a-z 0-9)"
        placeholderTextColor="#6B7AA8"
        autoCapitalize="none"
        autoCorrect={false}
        editable={!running}
      />

      <View style={styles.box}>
        <Row ok={codeOk} label="Room code sahi hai" />
        <Row ok={overlayOk} label="Display over other apps permission" />
        <Row ok={dbOk} label="Firebase URL set hai" />
      </View>

      {!overlayOk && (
        <TouchableOpacity style={styles.btnAlt} onPress={onOverlayPerm}>
          <Text style={styles.btnText}>Overlay permission do</Text>
        </TouchableOpacity>
      )}

      {running ? (
        <TouchableOpacity style={styles.btnStop} onPress={onStop}>
          <Text style={styles.btnText}>Stop Sharing</Text>
        </TouchableOpacity>
      ) : (
        <TouchableOpacity style={styles.btn} onPress={onStart}>
          <Text style={styles.btnText}>Start Sharing</Text>
        </TouchableOpacity>
      )}

      <Text style={styles.status}>{running ? 'Sharing ON' : 'Sharing OFF'}</Text>
      {msg ? <Text style={styles.msg}>{msg}</Text> : null}

      <Text style={styles.foot}>Version 1.1.0 (text chat overlay)</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0B1738' },
  content: { padding: 24, paddingTop: 64, alignItems: 'center' },
  title: { color: '#FFFFFF', fontSize: 34, fontWeight: '800' },
  sub: { color: '#9FB0E0', fontSize: 15, marginTop: 8, marginBottom: 24 },
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
  box: {
    width: '100%',
    backgroundColor: '#101F4D',
    borderRadius: 14,
    padding: 14,
    marginTop: 16,
  },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 5 },
  dot: { width: 34, fontWeight: '800', fontSize: 13 },
  dotOk: { color: '#4ADE80' },
  dotNo: { color: '#FF6FAE' },
  rowText: { color: '#C9D4F5', fontSize: 14 },
  btn: {
    width: '100%',
    backgroundColor: '#4F8CFF',
    borderRadius: 14,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: 16,
  },
  btnAlt: {
    width: '100%',
    backgroundColor: '#7C5CFF',
    borderRadius: 14,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: 16,
  },
  btnStop: {
    width: '100%',
    backgroundColor: '#FF4D6D',
    borderRadius: 14,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: 16,
  },
  btnText: { color: '#FFFFFF', fontSize: 17, fontWeight: '700' },
  status: { color: '#9FB0E0', marginTop: 18, fontSize: 15, fontWeight: '700' },
  msg: { color: '#FF6FAE', marginTop: 12, textAlign: 'center', fontSize: 14 },
  foot: { color: '#51639C', fontSize: 12, marginTop: 36 },
});
