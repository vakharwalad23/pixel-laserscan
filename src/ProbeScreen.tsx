import * as React from 'react';
import { ActivityIndicator, Alert, PermissionsAndroid, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Capabilities, LaserProbe, LaserProfileInfo, SweepResult } from '../modules/laser-core/src';
import { KIND_DETAIL, KIND_LABEL, SOURCE_LABEL, kindTone } from './laser';
import { C, T } from './theme';
import { Button, Row, Segmented } from './ui';

export function ProbeScreen(props: { onSaved: (id: string) => void }) {
  const insets = useSafeAreaInsets();
  const [profile, setProfile] = React.useState<LaserProfileInfo>(() => LaserProbe.laserProfile());
  const [caps, setCaps] = React.useState<Capabilities | null>(null);
  const [capsError, setCapsError] = React.useState<string | null>(null);
  const [cameraId, setCameraId] = React.useState<string>('');
  const [grid, setGrid] = React.useState(5);
  const [sweep, setSweep] = React.useState<SweepResult | null>(null);
  const [progress, setProgress] = React.useState<{ done: number; total: number } | null>(null);
  const [showAllVendor, setShowAllVendor] = React.useState(false);

  React.useEffect(() => {
    LaserProbe.capabilities()
      .then((c) => {
        setCaps(c);
        const back = c.cameras.find((x) => x.facing === 'back' && x.afRegionsMax > 0 && !x.physical);
        if (back) setCameraId(back.id);
      })
      .catch((e) => setCapsError(String(e?.message ?? e)));
    const sub = LaserProbe.addListener('onSweepProgress', (p) => setProgress(p));
    return () => sub.remove();
  }, []);

  const runSweep = async (dark: boolean) => {
    const r = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
    if (r !== PermissionsAndroid.RESULTS.GRANTED) {
      Alert.alert('Camera access is off', 'Allow camera access to run a sweep.');
      return;
    }
    setSweep(null);
    const n = dark ? 5 : grid;
    setProgress({ done: 0, total: n * n });
    try {
      setSweep(await LaserProbe.sweep(cameraId, n, dark ? 250 : 350, dark));
    } catch (e: any) {
      Alert.alert(dark ? 'Laser test failed' : 'Sweep failed', String(e?.message ?? e));
    } finally {
      setProgress(null);
    }
  };

  const saveSweep = async () => {
    try {
      const r = await LaserProbe.saveLastSweep();
      props.onSaved(r.id);
    } catch (e: any) {
      Alert.alert('Could not save the sweep', String(e?.message ?? e));
    }
  };

  const useVerdict = () => {
    if (!sweep?.verdict) return;
    setProfile(LaserProbe.setObservedLaser(sweep.verdict.kind));
  };

  const backCams = caps?.cameras.filter((c) => c.facing === 'back') ?? [];
  const sweepable = backCams.filter((c) => c.afRegionsMax > 0 && !c.physical);
  const flaggedSensors = caps?.sensors.filter((s) => s.flagged) ?? [];
  const sweepCam = caps?.cameras.find((c) => c.id === cameraId);
  const manualOk = !!sweepCam?.manualSensor;
  const busy = !!progress;

  return (
    <ScrollView style={s.fill} contentContainerStyle={{ padding: 16, paddingTop: insets.top + 12, paddingBottom: 32 }}>
      <Text style={T.title}>Laser hardware</Text>
      <View style={s.card}>
        <Text style={[T.big, { color: kindTone(profile.kind) }]}>{KIND_LABEL[profile.kind]}</Text>
        <Text style={[T.small, { marginTop: 2 }]}>
          {profile.family}, {SOURCE_LABEL[profile.source] ?? profile.source}
        </Text>
        <Text style={[T.body, { marginTop: 10 }]}>{KIND_DETAIL[profile.kind]}</Text>
        {profile.observedKind && profile.observedKind !== profile.tableKind ? (
          <Text style={[T.small, { marginTop: 8, color: C.warn }]}>
            Your test says {KIND_LABEL[profile.observedKind].toLowerCase()}; the spec table says{' '}
            {KIND_LABEL[profile.tableKind].toLowerCase()}. The scanner follows your test.
          </Text>
        ) : (
          <Text style={[T.small, { marginTop: 8 }]}>{profile.note}</Text>
        )}
        {profile.observedKind ? (
          <Button
            label="Forget test result"
            onPress={() => setProfile(LaserProbe.setObservedLaser(null))}
            style={{ marginTop: 12 }}
          />
        ) : null}
      </View>

      <Text style={[T.title, { marginTop: 28 }]}>Laser test</Text>
      <Text style={[T.small, { marginTop: 4 }]}>
        Darkens the camera to its shortest exposure so phase-detect focus goes blind and only a laser can still
        focus, then focuses on 25 regions in turn. Aim at an object about 40 cm away with a wall well behind it,
        so near and far fill different parts of the frame. Prop the phone up; it takes about 30 seconds.
      </Text>
      {sweepable.length > 1 ? (
        <View style={{ marginTop: 12 }}>
          <Segmented
            options={sweepable.map((c) => ({ value: c.id, label: `Camera ${c.id}` }))}
            value={cameraId}
            onChange={setCameraId}
            disabled={busy}
          />
        </View>
      ) : null}
      <Button
        label={progress ? `Measuring ${progress.done} of ${progress.total}` : 'Run laser test'}
        kind="primary"
        onPress={() => runSweep(true)}
        disabled={!cameraId || busy || !manualOk}
        style={{ marginTop: 12 }}
      />
      {sweepCam && !manualOk ? (
        <Text style={[T.small, { color: C.warn, marginTop: 8 }]}>
          {"This camera doesn't allow manual exposure, so the laser test can't run on it."}
        </Text>
      ) : null}

      {sweep?.verdict ? (
        <View style={s.card}>
          <Text style={[T.big, { color: kindTone(sweep.verdict.kind) }]}>{KIND_LABEL[sweep.verdict.kind]}</Text>
          <Text style={[T.body, { marginTop: 6 }]}>{sweep.verdict.reason}</Text>
          <Text style={[T.small, { marginTop: 6 }]}>
            {sweep.verdict.validCells} of {sweep.cells.length} regions locked, {sweep.verdict.distinct} distinct
            distances, frame brightness {sweep.meanLuma >= 0 ? Math.round(sweep.meanLuma) : '--'} of 255
            {sweep.verdict.confident ? '' : '. Not conclusive yet'}.
          </Text>
          {sweep.verdict.kind !== profile.kind || profile.source !== 'observed' ? (
            <Button
              label="Use this result for scanning"
              kind={sweep.verdict.confident ? 'primary' : 'plain'}
              onPress={useVerdict}
              style={{ marginTop: 12 }}
            />
          ) : (
            <Text style={[T.small, { marginTop: 8, color: C.agree }]}>The scanner is using this result.</Text>
          )}
        </View>
      ) : null}

      <Text style={[T.title, { marginTop: 28 }]}>Focus grid</Text>
      <Text style={[T.small, { marginTop: 4 }]}>
        Same sweep with normal exposure: a coarse depth image from focus alone, laser and phase-detect together.
        Save it to view as a point cloud.
      </Text>
      <View style={{ marginTop: 12 }}>
        <Segmented
          options={[
            { value: 3, label: '3 x 3' },
            { value: 5, label: '5 x 5' },
            { value: 8, label: '8 x 8' },
          ]}
          value={grid}
          onChange={setGrid}
          disabled={busy}
        />
      </View>
      <View style={{ flexDirection: 'row', gap: 12, marginTop: 12 }}>
        <Button label="Run focus grid" onPress={() => runSweep(false)} disabled={!cameraId || busy} style={{ flex: 1 }} />
        {sweep ? <Button label="Save as point cloud" onPress={saveSweep} disabled={busy} style={{ flex: 1 }} /> : null}
      </View>

      {sweep ? <SweepGrid sweep={sweep} /> : null}

      <Text style={[T.title, { marginTop: 28 }]}>What this phone exposes</Text>
      <Text style={[T.small, { marginTop: 4 }]}>
        {"Apps can't read the laser's raw zones. They can read the focus distance it drives, and any " +
          'vendor camera keys the maker chose to publish.'}
      </Text>

      {capsError ? <Text style={[T.body, { color: C.warn, marginTop: 12 }]}>{capsError}</Text> : null}
      {!caps && !capsError ? <ActivityIndicator color={C.survey} style={{ marginTop: 24 }} /> : null}

      {caps ? (
        <View style={s.card}>
          <Row label="Device" value={`${caps.device.manufacturer} ${caps.device.model}`} />
          <Row label="Android" value={`API ${caps.device.sdk}`} />
          {caps.device.soc ? <Row label="Chip" value={caps.device.soc} /> : null}
          <Row
            label="ARCore"
            value={caps.arcore.replace(/_/g, ' ').toLowerCase()}
            tone={caps.arcore.startsWith('SUPPORTED') ? C.agree : C.warn}
          />
          <Row
            label="Range sensors in sensor list"
            value={flaggedSensors.length ? flaggedSensors.map((x) => x.name).join(', ') : 'none'}
          />
        </View>
      ) : null}

      {backCams.map((cam) => {
        const vendorFlags = Object.entries(cam.flaggedCharacteristics);
        const flaggedResult = cam.vendorResultKeys.filter((k) =>
          /(tof|laser|ldaf|depth|distance|range|zone|spad|vl53|flight|pdaf|focus)/i.test(k),
        );
        return (
          <View key={cam.id} style={s.card}>
            <Text style={[T.body, { fontWeight: '600', marginBottom: 4 }]}>
              Back camera {cam.id}
              {cam.physical ? ' (physical lens of a logical camera)' : ''}
            </Text>
            <Row
              label="Focus distance"
              value={cam.focusCalibration}
              tone={cam.focusCalibration === 'calibrated' ? C.agree : cam.focusCalibration === 'approximate' ? C.survey : C.warn}
            />
            <Row label="Closest focus" value={cam.minFocusM > 0 ? `${(cam.minFocusM * 100).toFixed(1)} cm` : 'fixed focus'} />
            <Row label="Hyperfocal" value={cam.hyperfocalM > 0 ? `${cam.hyperfocalM.toFixed(2)} m` : 'n/a'} />
            <Row label="AF regions" value={String(cam.afRegionsMax)} />
            <Row label="Manual exposure" value={cam.manualSensor ? 'yes' : 'no'} />
            <Row label="Depth stream" value={cam.depthFormats.length ? cam.depthFormats.join(', ') : 'none'} />
            <Row label="Vendor result keys" value={`${cam.vendorResultKeys.length} total, ${flaggedResult.length} look range-related`} />
            {flaggedResult.slice(0, showAllVendor ? 200 : 6).map((k) => (
              <Text key={k} style={s.key}>{k}</Text>
            ))}
            {vendorFlags.length ? (
              <>
                <Text style={[T.small, { marginTop: 8 }]}>Range-related vendor characteristics</Text>
                {vendorFlags.slice(0, showAllVendor ? 200 : 6).map(([k, v]) => (
                  <Text key={k} style={s.key}>{k} = {v}</Text>
                ))}
              </>
            ) : null}
          </View>
        );
      })}
      {backCams.some((c) => c.vendorResultKeys.length > 6) ? (
        <Button
          label={showAllVendor ? 'Show fewer keys' : 'Show all range-related keys'}
          onPress={() => setShowAllVendor((v) => !v)}
          style={{ marginTop: 8 }}
        />
      ) : null}
    </ScrollView>
  );
}

function SweepGrid({ sweep }: { sweep: SweepResult }) {
  const valid = sweep.cells.filter((c) => c.meters > 0);
  const zs = valid.map((c) => c.meters);
  const zMin = Math.min(...zs);
  const zMax = Math.max(...zs);
  const distinct = new Set(valid.map((c) => c.meters.toFixed(2))).size;
  const vendorKeys = new Set(sweep.cells.flatMap((c) => Object.keys(c.vendor)));
  return (
    <View style={{ marginTop: 16 }}>
      <Text style={[T.small, { marginBottom: 6 }]}>{sweep.dark ? 'Laser test result' : 'Focus grid result'}</Text>
      <View style={[s.grid, { aspectRatio: sweep.width / sweep.height }]}>
        {Array.from({ length: sweep.grid }).map((_, row) => (
          <View key={row} style={{ flex: 1, flexDirection: 'row' }}>
            {sweep.cells
              .filter((c) => c.row === row)
              .map((c) => {
                const t = c.meters > 0 && zMax > zMin ? (c.meters - zMin) / (zMax - zMin) : 0.5;
                return (
                  <View
                    key={c.col}
                    style={[
                      s.cell,
                      { backgroundColor: c.meters > 0 ? depthColor(t) : C.ink },
                      !c.focused && { opacity: 0.55 },
                    ]}
                  >
                    <Text style={[T.num, { fontSize: sweep.grid >= 8 ? 10 : 13, color: '#0B1218' }]}>
                      {c.meters > 0 ? (c.meters * 100).toFixed(sweep.grid >= 8 ? 0 : 1) : '--'}
                    </Text>
                  </View>
                );
              })}
          </View>
        ))}
      </View>
      <Text style={[T.small, { marginTop: 8 }]}>
        Centimetres per region, near is warm, faded = no focus lock. {valid.length} of {sweep.cells.length} measured,{' '}
        {distinct} distinct values{sweep.lensReset ? ', lens parked at infinity between regions' : ''}.
        {vendorKeys.size ? ` Vendor keys seen: ${Array.from(vendorKeys).join(', ')}.` : ''}
      </Text>
    </View>
  );
}

// near (t=0) warm yellow -> far (t=1) cool blue
function depthColor(t: number): string {
  const a = [242, 194, 48];
  const b = [70, 130, 190];
  const c = a.map((v, i) => Math.round(v + (b[i] - v) * t));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

const s = StyleSheet.create({
  fill: { flex: 1, backgroundColor: C.ink },
  card: { backgroundColor: C.panel, borderRadius: 14, padding: 14, marginTop: 14 },
  key: { color: C.muted, fontSize: 12, marginTop: 2 },
  grid: { width: '100%', borderRadius: 10, overflow: 'hidden', backgroundColor: C.panel },
  cell: { flex: 1, margin: 1, alignItems: 'center', justifyContent: 'center' },
});
