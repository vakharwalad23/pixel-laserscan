import { StatusBar } from 'expo-status-bar';
import * as React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';

import { LibraryScreen } from './src/LibraryScreen';
import { ProbeScreen } from './src/ProbeScreen';
import { ScanScreen } from './src/ScanScreen';
import { C } from './src/theme';

type Tab = 'scan' | 'probe' | 'library';

const TABS: { key: Tab; label: string }[] = [
  { key: 'scan', label: 'Scan' },
  { key: 'probe', label: 'Probe' },
  { key: 'library', label: 'Library' },
];

function Shell() {
  const insets = useSafeAreaInsets();
  const [tab, setTab] = React.useState<Tab>('scan');
  const [openId, setOpenId] = React.useState<string | null>(null);

  const openScan = React.useCallback((id: string) => {
    setOpenId(id);
    setTab('library');
  }, []);
  const clearOpen = React.useCallback(() => setOpenId(null), []);

  // Only one screen is mounted at a time so the camera is released when you leave Scan.
  return (
    <View style={s.fill}>
      <View style={s.fill}>
        {tab === 'scan' ? <ScanScreen onSaved={openScan} /> : null}
        {tab === 'probe' ? <ProbeScreen onSaved={openScan} /> : null}
        {tab === 'library' ? <LibraryScreen openId={openId} onOpened={clearOpen} /> : null}
      </View>
      <View style={[s.tabs, { paddingBottom: Math.max(insets.bottom, 8) }]}>
        {TABS.map((t) => {
          const on = t.key === tab;
          return (
            <Pressable
              key={t.key}
              onPress={() => setTab(t.key)}
              style={s.tab}
              accessibilityRole="tab"
              accessibilityState={{ selected: on }}
            >
              <View style={[s.tabMark, on && s.tabMarkOn]} />
              <Text style={[s.tabText, on && s.tabTextOn]}>{t.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <Shell />
    </SafeAreaProvider>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1, backgroundColor: C.ink },
  tabs: { flexDirection: 'row', backgroundColor: C.ink, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.line },
  tab: { flex: 1, alignItems: 'center', paddingTop: 8, paddingBottom: 4 },
  tabMark: { width: 18, height: 3, borderRadius: 2, backgroundColor: 'transparent', marginBottom: 6 },
  tabMarkOn: { backgroundColor: C.survey },
  tabText: { color: C.muted, fontSize: 14, fontWeight: '500' },
  tabTextOn: { color: C.text },
});
