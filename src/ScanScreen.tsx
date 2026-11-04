import * as React from 'react';
import { Alert, PermissionsAndroid, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ScanError, ScanMode, ScanStatus, ScanView, ScanViewRef } from '../modules/laser-core/src';
import { KIND_LABEL, kindTone } from './laser';
import { C, T, fmtCount, fmtMm } from './theme';
import { Button, Segmented } from './ui';

const LASER_TEXT: Record<string, string> = {
  idle: 'Waiting for the camera',
  ok: 'Laser range locked',
  focusing: 'Lens is refocusing',
  unfocused: 'No focus lock on the target',
  stale: 'Moved a lot without refocusing; pause briefly',
  disagrees: 'Laser and ARCore disagree; skipping this reading',
  edge: 'Laser cone spans an edge; skipping this reading',
  unverified: 'No depth to check against; pause so the lens settles',
  infinity: 'Target is beyond the focus range',
  beyond_hyperfocal: 'Too far for a laser range',
  too_close: 'Too close; back off a little',
  out_of_range: 'Beyond the laser range limit',
  no_metadata: 'This phone reports no focus metadata',
  no_focus_key: 'This phone reports no focus distance',
};

const TRACKING_TEXT: Record<string, string> = {
  INSUFFICIENT_LIGHT: 'Too dark to track. Add light.',
  EXCESSIVE_MOTION: 'Moving too fast. Slow down.',
  INSUFFICIENT_FEATURES: 'Point at something with texture.',
  CAMERA_UNAVAILABLE: 'Camera is busy.',
  BAD_STATE: 'Tracking lost. Hold still.',
};

const VOXELS = [
  { value: 0.006, label: 'Fine 6 mm' },
  { value: 0.01, label: 'Object 1 cm' },
  { value: 0.02, label: 'Room 2 cm' },
];

function modesFor(noLaser: boolean): { value: ScanMode; label: string }[] {
  return [
    { value: 'fused', label: noLaser ? 'Focus + depth' : 'Laser + depth' },
    { value: 'laser', label: noLaser ? 'Focus only' : 'Laser only' },
    { value: 'depth', label: 'Depth only' },
  ];
}

export function ScanScreen(props: { onSaved: (id: string) => void }) {
  const insets = useSafeAreaInsets();
  const ref = React.useRef<ScanViewRef>(null);
  const [granted, setGranted] = React.useState<boolean | null>(null);
  const [status, setStatus] = React.useState<ScanStatus | null>(null);
  const [error, setError] = React.useState<ScanError | null>(null);
  const [scanning, setScanning] = React.useState(false);
  const [mode, setMode] = React.useState<ScanMode>('fused');
  const [voxel, setVoxel] = React.useState(0.01);
  const [applyScale, setApplyScale] = React.useState(false);
  const [saving, setSaving] = React.useState(false);

  React.useEffect(() => {
    PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA).then((r) =>
      setGranted(r === PermissionsAndroid.RESULTS.GRANTED),
    );
  }, []);

  const save = async () => {
    if (!ref.current) return;
    setSaving(true);
    setScanning(false);
    try {
      const r = await ref.current.save(null);
      Alert.alert(
        'Scan saved',
        `${fmtCount(r.vertices)} mesh vertices, ${fmtCount(r.cloudPoints)} cloud points, ${fmtCount(r.laserPoints)} laser hits.`,
        [
          { text: 'Keep scanning' },
          { text: 'Open', onPress: () => props.onSaved(r.id) },
        ],
      );
    } catch (e: any) {
      Alert.alert('Could not save the scan', String(e?.message ?? e));
    } finally {
      setSaving(false);
    }
  };

  const reset = () => {
    ref.current?.reset();
  };

  if (granted === false) {
    return (
      <View style={[s.center, { paddingTop: insets.top }]}>
        <Text style={T.title}>Camera access is off</Text>
        <Text style={[T.small, { marginTop: 8, textAlign: 'center' }]}>
          Scanning needs the camera. Allow it in Android settings, then reopen this tab.
        </Text>
      </View>
    );
  }
  if (granted === null) return <View style={s.fill} />;

  const st = status;
  const laserOk = st?.laserState === 'ok';
  const tracking = st?.tracking === 'TRACKING';
  const delta =
    st && st.laserMm > 0 && st.arcoreCenterMm > 0 ? (st.laserMm / st.arcoreCenterMm - 1) * 100 : null;
  const scaleTrusted = !!st && st.scaleCount >= 20 && st.scaleMad < 0.05;
  const hint =
    error?.message ??
    (!st
      ? 'Starting ARCore'
      : !tracking
        ? (TRACKING_TEXT[st.trackingFailure] ?? 'Move the phone slowly to start tracking.')
        : !st.depthSupported && mode !== 'laser'
          ? 'This phone has no ARCore depth. Laser-only mode still works.'
          : st.blockLimit
            ? 'Memory budget reached. Save, or reset to continue.'
            : null);

  return (
    <View style={s.fill}>
      <ScanView
        ref={ref}
        style={StyleSheet.absoluteFill}
        scanning={scanning}
        mode={mode}
        voxelSize={voxel}
        maxDepth={voxel >= 0.02 ? 4 : 2.2}
        laserProfile="auto"
        applyLaserScale={applyScale}
        pointSize={voxel >= 0.02 ? 9 : 6}
        onStatus={(e) => setStatus(e.nativeEvent)}
        onScanError={(e) => setError(e.nativeEvent)}
      />

      <View style={[s.top, { paddingTop: insets.top + 8 }]} pointerEvents="none">
        <View style={[s.dot, { backgroundColor: tracking ? C.agree : C.warn }]} />
        <Text style={s.topText}>
          {tracking ? 'Tracking' : 'Not tracking'}
          {st ? `   ${Math.round(st.fps)} fps` : ''}
          {st && !st.depthSupported ? '   no depth' : ''}
        </Text>
        <View style={{ flex: 1 }} />
        {st ? (
          <Text style={[s.topText, { color: kindTone(st.laserKind) }]}>
            {KIND_LABEL[st.laserKind]}
            {st.laserSource === 'observed' ? ', tested' : ''}
          </Text>
        ) : null}
      </View>

      <View style={[s.reticle, laserOk && { borderColor: C.laser }]} pointerEvents="none" />
      <View style={s.readout} pointerEvents="none">
        <Text style={[T.hero, { color: laserOk ? C.text : C.muted }]}>
          {fmtMm(st?.laserMm ?? -1)}
          <Text style={[T.big, { color: C.muted }]}> mm</Text>
        </Text>
        <Text style={[T.small, { color: laserOk ? C.laser : C.muted }]}>
          {LASER_TEXT[st?.laserState ?? 'idle'] ?? st?.laserState}
        </Text>
        {st && st.arcoreCenterMm > 0 ? (
          <Text style={[T.small, { marginTop: 2 }]}>
            ARCore depth {fmtMm(st.arcoreCenterMm)} mm
            {delta !== null ? `, laser ${delta >= 0 ? '+' : ''}${delta.toFixed(1)}%` : ''}
          </Text>
        ) : null}
      </View>

      <View style={[s.sheet, { paddingBottom: insets.bottom + 12 }]}>
        {hint ? <Text style={s.hint}>{hint}</Text> : null}

        <View style={s.stats}>
          <Stat label="surface pts" value={fmtCount(st?.points ?? 0)} />
          <Stat
            label={st?.laserKind === 'none' ? 'focus hits' : 'laser hits'}
            value={fmtCount(st?.laserPoints ?? 0)}
            tone={C.laser}
          />
          <Stat label="depth frames" value={fmtCount(st?.depthFrames ?? 0)} />
          <Stat label="fuse ms" value={st ? st.integrateMs.toFixed(1) : '--'} />
        </View>

        <Segmented
          options={modesFor(st?.laserKind === 'none')}
          value={mode}
          onChange={setMode}
          disabled={saving}
        />
        <View style={{ height: 8 }} />
        <Segmented
          options={VOXELS}
          value={voxel}
          disabled={saving}
          onChange={(v) => {
            if (v === voxel) return;
            if ((st?.points ?? 0) > 0) {
              Alert.alert('Change resolution?', 'This clears the current scan.', [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Clear and change', style: 'destructive', onPress: () => setVoxel(v) },
              ]);
            } else setVoxel(v);
          }}
        />

        <View style={s.scaleRow}>
          <View style={{ flex: 1 }}>
            <Text style={T.body}>Scale the model to the laser</Text>
            <Text style={T.small}>
              {applyScale && st && st.lockedScale !== 1
                ? `Locked at x${st.lockedScale.toFixed(3)}; applied when you save`
                : st && st.scaleCount > 0
                  ? `Laser / ARCore x${st.scaleMedian.toFixed(3)} from ${st.scaleCount} samples` +
                    (scaleTrusted ? '' : ', needs 20 steady samples to lock')
                  : 'Compares laser and ARCore distances while scanning'}
            </Text>
          </View>
          <Switch
            value={applyScale}
            onValueChange={setApplyScale}
            trackColor={{ true: C.survey, false: C.line }}
            thumbColor={C.text}
          />
        </View>

        <View style={s.actions}>
          <Button label="Reset" onPress={reset} disabled={saving} style={{ flex: 1 }} />
          <Pressable
            onPress={() => setScanning((v) => !v)}
            disabled={saving || !tracking}
            accessibilityRole="button"
            accessibilityLabel={scanning ? 'Pause scanning' : 'Start scanning'}
            style={({ pressed }) => [s.shutter, (!tracking || saving) && { opacity: 0.4 }, pressed && { opacity: 0.8 }]}
          >
            <View style={[s.shutterInner, scanning && s.shutterOn]} />
          </Pressable>
          <Button
            label={saving ? 'Saving' : 'Save'}
            onPress={save}
            disabled={saving || (st?.points ?? 0) === 0}
            style={{ flex: 1 }}
          />
        </View>
      </View>
    </View>
  );
}

function Stat(props: { label: string; value: string; tone?: string }) {
  return (
    <View style={{ flex: 1 }}>
      <Text style={[T.num, { fontSize: 18, color: props.tone ?? C.text }]}>{props.value}</Text>
      <Text style={[T.small, { fontSize: 12 }]}>{props.label}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1, backgroundColor: '#000' },
  center: { flex: 1, backgroundColor: C.ink, alignItems: 'center', justifyContent: 'center', padding: 32 },
  top: { position: 'absolute', left: 16, right: 16, flexDirection: 'row', alignItems: 'center', gap: 8 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  topText: { color: C.text, fontSize: 13, textShadowColor: '#000', textShadowRadius: 4 },
  readout: { position: 'absolute', bottom: '50%', marginBottom: 40, left: 0, right: 0, alignItems: 'center' },
  reticle: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    marginTop: -22,
    marginLeft: -22,
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 2,
    borderColor: 'rgba(230,237,242,0.7)',
  },
  sheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(19,32,43,0.94)',
    borderTopLeftRadius: 18,
    borderTopRightRadius: 18,
    paddingHorizontal: 16,
    paddingTop: 14,
  },
  hint: { color: C.warn, fontSize: 14, marginBottom: 10 },
  stats: { flexDirection: 'row', marginBottom: 12, gap: 8 },
  scaleRow: { flexDirection: 'row', alignItems: 'center', marginTop: 12, gap: 12 },
  actions: { flexDirection: 'row', alignItems: 'center', gap: 16, marginTop: 14 },
  shutter: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 3,
    borderColor: C.survey,
    alignItems: 'center',
    justifyContent: 'center',
  },
  shutterInner: { width: 56, height: 56, borderRadius: 28, backgroundColor: C.survey },
  shutterOn: { width: 26, height: 26, borderRadius: 6 },
});
