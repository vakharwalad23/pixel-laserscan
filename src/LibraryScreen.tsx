import * as React from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { LaserLibrary, ModelView, ScanEntry } from '../modules/laser-core/src';
import { C, T, fmtBytes, fmtCount } from './theme';
import { Button, Segmented } from './ui';

type Meta = {
  name?: string;
  kind?: 'scan' | 'probe';
  createdAt?: number;
  mode?: string;
  voxelSize?: number;
  vertices?: number;
  triangles?: number;
  cloudPoints?: number;
  laserPoints?: number;
  scaleCount?: number;
  scaleMedian?: number;
  device?: string;
};

function parseMeta(e: ScanEntry): Meta {
  try {
    return JSON.parse(e.meta) as Meta;
  } catch {
    return {};
  }
}

export function LibraryScreen(props: { openId: string | null; onOpened: () => void }) {
  const insets = useSafeAreaInsets();
  const [items, setItems] = React.useState<ScanEntry[] | null>(null);
  const [open, setOpen] = React.useState<ScanEntry | null>(null);

  const refresh = React.useCallback(async () => {
    const list = await LaserLibrary.list();
    setItems(list);
    return list;
  }, []);

  const { openId, onOpened } = props;
  React.useEffect(() => {
    let alive = true;
    LaserLibrary.list().then((list) => {
      if (!alive) return;
      setItems(list);
      if (openId) {
        const hit = list.find((x) => x.id === openId);
        if (hit) setOpen(hit);
        onOpened();
      }
    });
    return () => {
      alive = false;
    };
  }, [openId, onOpened]);

  if (open) return <Viewer entry={open} onClose={() => setOpen(null)} onDeleted={() => { setOpen(null); refresh(); }} />;

  return (
    <View style={[s.fill, { paddingTop: insets.top + 12 }]}>
      <Text style={[T.title, { paddingHorizontal: 16 }]}>Scans</Text>
      <FlatList
        data={items ?? []}
        keyExtractor={(x) => x.id}
        contentContainerStyle={{ padding: 16, gap: 10 }}
        onRefresh={refresh}
        refreshing={items === null}
        ListEmptyComponent={
          items ? (
            <Text style={[T.small, { marginTop: 24 }]}>
              No scans yet. Start one on the Scan tab, or save a sweep from Probe.
            </Text>
          ) : undefined
        }
        renderItem={({ item }) => {
          const m = parseMeta(item);
          const when = new Date(m.createdAt ?? item.modifiedAt);
          return (
            <Pressable onPress={() => setOpen(item)} style={({ pressed }) => [s.item, pressed && { opacity: 0.8 }]}>
              <View style={{ flex: 1 }}>
                <Text style={T.body}>{m.kind === 'probe' ? 'Zone sweep' : modeName(m.mode)}</Text>
                <Text style={T.small}>
                  {when.toLocaleDateString()} {when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  {m.voxelSize ? `, ${Math.round(m.voxelSize * 1000)} mm voxels` : ''}
                </Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={T.num}>
                  {m.kind === 'probe' ? `${m.cloudPoints ?? 0} cells` : `${fmtCount(m.triangles ?? 0)} tris`}
                </Text>
                {m.laserPoints ? <Text style={[T.small, { color: C.laser }]}>{fmtCount(m.laserPoints)} laser hits</Text> : null}
              </View>
            </Pressable>
          );
        }}
      />
    </View>
  );
}

function modeName(mode?: string) {
  if (mode === 'laser') return 'Laser-only scan';
  if (mode === 'depth') return 'Depth-only scan';
  return 'Laser + depth scan';
}

type Layer = 'mesh.ply' | 'cloud.ply' | 'laser.ply';

function Viewer(props: { entry: ScanEntry; onClose: () => void; onDeleted: () => void }) {
  const insets = useSafeAreaInsets();
  const m = parseMeta(props.entry);
  const has = (n: string) => props.entry.files.some((f) => f.name === n);
  const layers = (
    [
      { value: 'mesh.ply', label: 'Mesh' },
      { value: 'cloud.ply', label: 'Points' },
      { value: 'laser.ply', label: 'Laser hits' },
    ] as { value: Layer; label: string }[]
  ).filter((l) => has(l.value));
  const [layer, setLayer] = React.useState<Layer | null>(layers[0]?.value ?? null);
  const [loadInfo, setLoadInfo] = React.useState<string>('');
  const path = layer ? props.entry.files.find((f) => f.name === layer)?.path ?? '' : '';

  const remove = () =>
    Alert.alert('Delete this scan?', 'Its files are removed from the phone.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          await LaserLibrary.remove(props.entry.id);
          props.onDeleted();
        },
      },
    ]);

  return (
    <View style={s.fill}>
      {path ? (
        <ModelView
          style={StyleSheet.absoluteFill}
          source={path}
          pointSize={layer === 'laser.ply' ? 9 : 4}
          onModelLoad={(e) => {
            const d = e.nativeEvent;
            setLoadInfo(
              d.ok
                ? d.triangles
                  ? `${fmtCount(d.vertices ?? 0)} vertices, ${fmtCount(d.triangles)} triangles`
                  : `${fmtCount(d.vertices ?? 0)} points`
                : (d.error ?? 'Could not load the file'),
            );
          }}
        />
      ) : (
        <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}>
          <Text style={T.small}>This scan has no viewable files.</Text>
        </View>
      )}
      <View style={[s.viewerTop, { paddingTop: insets.top + 8 }]}>
        <Button label="Back" onPress={props.onClose} />
        <View style={{ flex: 1 }}>
          <Text style={T.small} numberOfLines={1}>{loadInfo}</Text>
          <Text style={[T.small, { fontSize: 12 }]}>Drag to orbit, pinch to zoom, double-tap to reset</Text>
        </View>
      </View>
      <View style={[s.viewerSheet, { paddingBottom: insets.bottom + 12 }]}>
        {layers.length > 1 && layer ? <Segmented options={layers} value={layer} onChange={setLayer} /> : null}
        {m.scaleCount ? (
          <Text style={[T.small, { marginTop: 10 }]}>
            Laser vs ARCore depth during this scan: x{(m.scaleMedian ?? 1).toFixed(3)} over {m.scaleCount} samples
          </Text>
        ) : null}
        <Text style={[T.small, { marginTop: 10 }]}>Share a file</Text>
        <View style={s.files}>
          {props.entry.files
            .filter((f) => f.name !== 'meta.json')
            .map((f) => (
              <Pressable
                key={f.name}
                onPress={() => LaserLibrary.share(f.path).catch((e) => Alert.alert('Could not share', String(e)))}
                style={({ pressed }) => [s.file, pressed && { opacity: 0.7 }]}
              >
                <Text style={T.body}>{f.name}</Text>
                <Text style={[T.small, { fontSize: 12 }]}>{fmtBytes(f.bytes)}</Text>
              </Pressable>
            ))}
        </View>
        <Button label="Delete scan" kind="danger" onPress={remove} style={{ marginTop: 12 }} />
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1, backgroundColor: C.ink },
  item: { flexDirection: 'row', alignItems: 'center', backgroundColor: C.panel, borderRadius: 14, padding: 14, gap: 12 },
  viewerTop: { position: 'absolute', left: 16, right: 16, flexDirection: 'row', alignItems: 'center', gap: 12 },
  viewerSheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(19,32,43,0.94)',
    borderTopLeftRadius: 18,
    borderTopRightRadius: 18,
    padding: 16,
  },
  files: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 6 },
  file: { backgroundColor: C.raised, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8 },
});
