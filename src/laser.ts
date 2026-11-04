import type { LaserKind } from '../modules/laser-core/src';
import { C } from './theme';

export const KIND_LABEL: Record<LaserKind, string> = {
  multi: 'Multi-zone laser',
  single: 'Single-zone laser',
  laser: 'Laser, zone count unknown',
  none: 'No laser, focus distance only',
  unknown: 'Laser not identified',
};

export const KIND_DETAIL: Record<LaserKind, string> = {
  multi:
    'Each autofocus region maps to its own laser zone, so readings are local and trusted out to 3.5 m.',
  single:
    'One wide laser cone (about 25 degrees). Readings near a depth edge are checked against ARCore and dropped if they blend surfaces. Range 2.5 m.',
  laser:
    'Has laser autofocus but the zone count is not published. Treated like single-zone until the laser test says otherwise.',
  none:
    'Focus distance comes from phase-detect AF only. Used as a weak hint up to 1.2 m.',
  unknown:
    'Not in the model table. Treated cautiously; run the laser test to identify it.',
};

export const SOURCE_LABEL: Record<string, string> = {
  observed: 'from your laser test',
  'model table': "from Google's published specs",
  unknown: 'not identified yet',
  manual: 'set manually',
};

export function kindTone(kind: LaserKind): string {
  if (kind === 'multi' || kind === 'single' || kind === 'laser') return C.laser;
  if (kind === 'none') return C.muted;
  return C.warn;
}
