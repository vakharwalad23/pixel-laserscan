import * as React from 'react';
import { Pressable, StyleSheet, Text, View, ViewStyle } from 'react-native';

import { C } from './theme';

export function Segmented<T extends string | number>(props: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
  disabled?: boolean;
}) {
  return (
    <View style={s.seg}>
      {props.options.map((o) => {
        const on = o.value === props.value;
        return (
          <Pressable
            key={String(o.value)}
            disabled={props.disabled}
            onPress={() => props.onChange(o.value)}
            style={[s.segItem, on && s.segOn, props.disabled && { opacity: 0.5 }]}
            accessibilityRole="button"
            accessibilityState={{ selected: on, disabled: props.disabled }}
          >
            <Text style={[s.segText, on && s.segTextOn]}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Button(props: {
  label: string;
  onPress: () => void;
  kind?: 'primary' | 'plain' | 'danger';
  disabled?: boolean;
  style?: ViewStyle;
}) {
  const kind = props.kind ?? 'plain';
  return (
    <Pressable
      onPress={props.onPress}
      disabled={props.disabled}
      accessibilityRole="button"
      style={({ pressed }) => [
        s.btn,
        kind === 'primary' && s.btnPrimary,
        kind === 'danger' && s.btnDanger,
        pressed && { opacity: 0.75 },
        props.disabled && { opacity: 0.4 },
        props.style,
      ]}
    >
      <Text style={[s.btnText, kind === 'primary' && { color: C.surveyInk }]}>{props.label}</Text>
    </Pressable>
  );
}

export function Row(props: { label: string; value: React.ReactNode; tone?: string }) {
  return (
    <View style={s.row}>
      <Text style={s.rowLabel}>{props.label}</Text>
      <Text style={[s.rowValue, props.tone ? { color: props.tone } : null]} numberOfLines={2}>
        {props.value}
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  seg: {
    flexDirection: 'row',
    backgroundColor: C.ink,
    borderRadius: 10,
    padding: 3,
    gap: 3,
  },
  segItem: { flex: 1, paddingVertical: 8, borderRadius: 8, alignItems: 'center' },
  segOn: { backgroundColor: C.raised },
  segText: { color: C.muted, fontSize: 14, fontWeight: '500' },
  segTextOn: { color: C.text },
  btn: {
    paddingHorizontal: 16,
    paddingVertical: 11,
    borderRadius: 10,
    backgroundColor: C.raised,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnPrimary: { backgroundColor: C.survey },
  btnDanger: { backgroundColor: '#4A2626' },
  btnText: { color: C.text, fontSize: 15, fontWeight: '600' },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 6, gap: 12 },
  rowLabel: { color: C.muted, fontSize: 14, flexShrink: 0 },
  rowValue: { color: C.text, fontSize: 14, textAlign: 'right', flexShrink: 1 },
});
