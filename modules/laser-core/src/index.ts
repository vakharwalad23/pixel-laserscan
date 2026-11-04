import { NativeModule, requireNativeModule, requireNativeView } from 'expo';
import * as React from 'react';
import type { NativeSyntheticEvent, StyleProp, ViewStyle } from 'react-native';

export type ScanMode = 'fused' | 'laser' | 'depth';

// multi/single = zone count known; laser = has LDAF, zones unpublished; none = PDAF only.
export type LaserKind = 'multi' | 'single' | 'laser' | 'none' | 'unknown';

export type LaserProfileInfo = {
  kind: LaserKind;
  tableKind: LaserKind;
  observedKind: LaserKind | null;
  source: 'observed' | 'model table' | 'unknown' | 'manual';
  family: string;
  note: string;
  manufacturer: string;
  model: string;
  device: string;
  maxRangeM: number;
  coneHalfDeg: number;
};

export type LaserState =
  | 'idle'
  | 'ok'
  | 'focusing'
  | 'unfocused'
  | 'stale'
  | 'disagrees'
  | 'edge'
  | 'unverified'
  | 'uncalibrated'
  | 'infinity'
  | 'beyond_hyperfocal'
  | 'too_close'
  | 'out_of_range'
  | 'no_metadata'
  | 'no_focus_key';

export type ScanStatus = {
  tracking: string;
  trackingFailure: string;
  depthSupported: boolean;
  scanning: boolean;
  mode: ScanMode;
  laserState: LaserState;
  laserMm: number;
  diopters: number;
  afState: number;
  lensState: number;
  arcoreCenterMm: number;
  focusCalibration: string;
  minFocusM: number;
  hyperfocalM: number;
  cameraId: string;
  blocks: number;
  points: number;
  laserPoints: number;
  depthFrames: number;
  integrateMs: number;
  blockLimit: boolean;
  scaleCount: number;
  scaleMedian: number;
  scaleMad: number;
  // laser/ARCore scale, 1 until trusted; converts laser hits, scales the saved model if applied
  lockedScale: number;
  saving: boolean;
  fps: number;
  laserKind: LaserKind;
  laserSource: string;
  laserFamily: string;
  laserRangeM: number;
  laserWeight: number;
  coneSpread: number;
};

export type ScanError = { code: string; message: string };

export type SaveResult = {
  id: string;
  path: string;
  vertices: number;
  triangles: number;
  cloudPoints: number;
  laserPoints: number;
};

export type ScanViewProps = {
  style?: StyleProp<ViewStyle>;
  scanning: boolean;
  mode: ScanMode;
  voxelSize: number;
  maxDepth: number;
  maxLaserRange?: number;
  applyLaserScale: boolean;
  pointSize: number;
  // 'auto' uses the detected profile (model table or Probe laser test).
  laserProfile?: 'auto' | LaserKind;
  onStatus?: (e: NativeSyntheticEvent<ScanStatus>) => void;
  onScanError?: (e: NativeSyntheticEvent<ScanError>) => void;
};

export type ScanViewRef = {
  reset(): Promise<void>;
  save(name: string | null): Promise<SaveResult>;
};

export type ModelViewProps = {
  style?: StyleProp<ViewStyle>;
  source: string;
  pointSize?: number;
  onModelLoad?: (
    e: NativeSyntheticEvent<{ ok: boolean; vertices?: number; triangles?: number; error?: string }>,
  ) => void;
};

export type CameraInfo = {
  id: string;
  // physical sub-camera of a logical camera: can't be opened directly
  physical: boolean;
  facing: 'back' | 'front' | 'external';
  hardwareLevel: number;
  depthOutput: boolean;
  logicalMulti: boolean;
  manualSensor: boolean;
  depthFormats: string[];
  focusCalibration: 'calibrated' | 'approximate' | 'uncalibrated';
  minFocusM: number;
  hyperfocalM: number;
  afRegionsMax: number;
  focalLengths: number[];
  intrinsics: number[];
  vendorCharacteristicKeys: string[];
  vendorResultKeys: string[];
  flaggedCharacteristics: Record<string, string>;
  error?: string;
};

export type SensorInfo = {
  name: string;
  vendor: string;
  type: number;
  stringType: string;
  maxRange: number;
  flagged: boolean;
};

export type Capabilities = {
  device: { manufacturer: string; model: string; device: string; sdk: number; soc: string };
  arcore: string;
  cameras: CameraInfo[];
  sensors: SensorInfo[];
};

export type SweepCell = {
  row: number;
  col: number;
  u: number;
  v: number;
  diopters: number;
  meters: number;
  focused: boolean;
  samples: number;
  vendor: Record<string, string>;
};

export type DarkVerdict = {
  kind: LaserKind;
  confident: boolean;
  validCells: number;
  distinct: number;
  ratio: number;
  reason: string;
};

export type SweepResult = {
  cameraId: string;
  grid: number;
  dark: boolean;
  meanLuma: number;
  lensReset: boolean;
  verdict: DarkVerdict | null;
  width: number;
  height: number;
  intrinsics: { fx: number; fy: number; cx: number; cy: number; fromCalibration: boolean };
  cells: SweepCell[];
};

export type ScanEntry = {
  id: string;
  path: string;
  modifiedAt: number;
  files: { name: string; path: string; bytes: number }[];
  meta: string;
};

type ProbeEvents = { onSweepProgress: (e: { done: number; total: number }) => void };

declare class LaserProbeModule extends NativeModule<ProbeEvents> {
  capabilities(): Promise<Capabilities>;
  laserProfile(): LaserProfileInfo;
  setObservedLaser(kind: LaserKind | null): LaserProfileInfo;
  // dark = starve PDAF (min exposure) so only the laser can focus: the laser test.
  sweep(cameraId: string, grid: number, settleMs: number, dark: boolean): Promise<SweepResult>;
  // stops a running sweep within ~0.2 s; its promise rejects with code CANCELLED
  cancelSweep(): void;
  saveLastSweep(): Promise<{ id: string; path: string; cloudPoints: number }>;
}

declare class LaserLibraryModule extends NativeModule {
  list(): Promise<ScanEntry[]>;
  remove(id: string): Promise<boolean>;
  share(path: string): Promise<boolean>;
}

declare class LaserScanModule extends NativeModule {
  arcoreAvailability(): Promise<string>;
}

export const LaserProbe = requireNativeModule<LaserProbeModule>('LaserProbe');
export const LaserLibrary = requireNativeModule<LaserLibraryModule>('LaserLibrary');
export const LaserScan = requireNativeModule<LaserScanModule>('LaserScan');

const NativeScanView = requireNativeView<ScanViewProps & { ref?: React.Ref<ScanViewRef> }>('LaserScan');
const NativeModelView = requireNativeView<ModelViewProps>('LaserLibrary');

export const ScanView = React.forwardRef<ScanViewRef, ScanViewProps>((props, ref) =>
  React.createElement(NativeScanView, { ...props, ref }),
);
ScanView.displayName = 'ScanView';

export function ModelView(props: ModelViewProps) {
  return React.createElement(NativeModelView, props);
}
