import { Platform, TextStyle } from 'react-native';

// Survey palette: total-station yellow for the one primary action, red only for laser data.
export const C = {
  ink: '#13202B',
  panel: '#1B2B38',
  raised: '#233645',
  line: '#2E4352',
  text: '#E6EDF2',
  muted: '#8EA3B4',
  faint: '#5D7385',
  survey: '#F2C230',
  surveyInk: '#2B2205',
  laser: '#FF4433',
  agree: '#5FD3A8',
  warn: '#F59E5B',
};

// Android ships Roboto Condensed as "sans-serif-condensed": used for readouts.
export const readout: TextStyle = {
  fontFamily: Platform.select({ android: 'sans-serif-condensed', default: undefined }),
  fontVariant: ['tabular-nums'],
};

export const T = {
  hero: { ...readout, fontSize: 56, lineHeight: 60, fontWeight: '300', letterSpacing: -1 } as TextStyle,
  big: { ...readout, fontSize: 28, lineHeight: 32, fontWeight: '400' } as TextStyle,
  title: { fontSize: 20, lineHeight: 26, fontWeight: '600', color: C.text } as TextStyle,
  body: { fontSize: 15, lineHeight: 21, color: C.text } as TextStyle,
  small: { fontSize: 13, lineHeight: 18, color: C.muted } as TextStyle,
  num: { ...readout, fontSize: 15, lineHeight: 20, color: C.text } as TextStyle,
};

export function fmtMm(mm: number): string {
  if (!(mm > 0)) return '--';
  return Math.round(mm).toLocaleString('en-US');
}

export function fmtCount(n: number): string {
  if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (n >= 1e4) return Math.round(n / 1e3) + 'k';
  return n.toLocaleString('en-US');
}

export function fmtBytes(b: number): string {
  if (b >= 1 << 20) return (b / (1 << 20)).toFixed(1) + ' MB';
  if (b >= 1 << 10) return Math.round(b / (1 << 10)) + ' KB';
  return b + ' B';
}
