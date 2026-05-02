import React, { useState } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  ImageBackground,
  Platform,
  Switch,
  StatusBar,
} from 'react-native';
import { BlurView } from 'react-native-blur-vibe';
import type { BlurType } from 'react-native-blur-vibe';

const BG_IMAGE = {
  uri: 'https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=800',
};

const BLUR_TYPES: BlurType[] = [
  'light',
  'dark',
  'extraLight',
  'regular',
  'prominent',
  'systemUltraThinMaterial',
  'systemThinMaterial',
  'systemMaterial',
  'systemThickMaterial',
  'systemChromeMaterial',
];

const OVERLAY_COLORS = [
  { label: 'transparent (pure blur)', value: '#00000000' },
  { label: '#RGB shorthand', value: '#000' },
  { label: '#RRGGBB (no alpha)', value: '#000000' },
  { label: '25% black tint', value: '#00000040' },
  { label: '50% black tint', value: '#00000080' },
  { label: '75% black tint', value: '#000000C0' },
  { label: '100% black (blur hidden)', value: '#000000FF' },
  { label: '30% white tint', value: '#FFFFFF50' },
  { label: '50% red tint', value: '#FF000080' },
  { label: 'default (no prop)', value: undefined },
];

export default function App() {
  const [showChildren, setShowChildren] = useState(true);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <StatusBar barStyle="light-content" />

      <Text style={styles.header}>react-native-blur-vibe</Text>
      <Text style={styles.subheader}>Prop Compatibility Test Suite</Text>
      <Text style={styles.platform}>
        Platform: {Platform.OS} {Platform.Version}
      </Text>

      {/* TEST 1: blurAmount range */}
      <Section title="TEST 1 — blurAmount (0, 5, 15, 30, 60, 100)">
        <View style={styles.row}>
          {[0, 5, 15, 30, 60, 100].map((amount) => (
            <ImageBackground key={amount} source={BG_IMAGE} style={styles.smallBox}>
              <BlurView blurAmount={amount} overlayColor="#00000000" style={StyleSheet.absoluteFill} />
              <Text style={styles.label}>{amount}</Text>
            </ImageBackground>
          ))}
        </View>
        <Text style={styles.note}>
          Expected: 0 = no blur, 100 = max blur. No crash on any value.
        </Text>
      </Section>

      {/* TEST 2: overlayColor all formats */}
      <Section title="TEST 2 — overlayColor all formats">
        {OVERLAY_COLORS.map(({ label, value }) => (
          <ImageBackground key={label} source={BG_IMAGE} style={styles.tallBox}>
            <BlurView
              blurAmount={15}
              overlayColor={value}
              style={StyleSheet.absoluteFill}
            />
            <Text style={styles.overlayLabel}>{label}</Text>
            <Text style={styles.overlayValue}>{value ?? 'undefined (platform default)'}</Text>
          </ImageBackground>
        ))}
        <Text style={styles.note}>
          Expected: No crash on any format. "#00000000" = pure blur. "#000000FF" = solid black.
        </Text>
      </Section>

      {/* TEST 3: blurType iOS */}
      <Section title={`TEST 3 — blurType (${Platform.OS === 'ios' ? 'iOS active' : 'Android no-op'})`}>
        {BLUR_TYPES.map((type) => (
          <ImageBackground key={type} source={BG_IMAGE} style={styles.tallBox}>
            <BlurView
              blurAmount={15}
              blurType={type}
              overlayColor="#00000000"
              style={StyleSheet.absoluteFill}
            />
            <Text style={styles.overlayLabel}>{type}</Text>
          </ImageBackground>
        ))}
        <Text style={styles.note}>
          iOS: Each shows a different blur material. Android: All same, no crash.
        </Text>
      </Section>

      {/* TEST 4: blurRadius */}
      <Section title="TEST 4 — blurRadius (Android downscale 1–8)">
        <View style={styles.row}>
          {[1, 2, 4, 6, 8].map((radius) => (
            <ImageBackground key={radius} source={BG_IMAGE} style={styles.smallBox}>
              <BlurView
                blurAmount={20}
                blurRadius={radius}
                overlayColor="#00000000"
                style={StyleSheet.absoluteFill}
              />
              <Text style={styles.label}>r={radius}</Text>
            </ImageBackground>
          ))}
        </View>
        <Text style={styles.note}>
          Android: Higher = slightly softer/faster. iOS: No difference (prop ignored).
        </Text>
      </Section>

      {/* TEST 5: reducedTransparencyFallbackColor */}
      <Section title="TEST 5 — reducedTransparencyFallbackColor">
        <ImageBackground source={BG_IMAGE} style={styles.tallBox}>
          <BlurView
            blurAmount={15}
            overlayColor="#00000030"
            reducedTransparencyFallbackColor="#FF6B6B"
            style={StyleSheet.absoluteFill}
          />
          <Text style={styles.overlayLabel}>fallback: #FF6B6B (coral red)</Text>
          <Text style={styles.overlayValue}>
            Enable Reduce Transparency in iOS Accessibility to see red
          </Text>
        </ImageBackground>
        <Text style={styles.note}>
          iOS: Enable Reduce Transparency → should show red. Android: Normal blur shown.
        </Text>
      </Section>

      {/* TEST 6: Children above blur */}
      <Section title="TEST 6 — Children render above blur layer">
        <ImageBackground source={BG_IMAGE} style={styles.tallBox}>
          <BlurView
            blurAmount={15}
            overlayColor="#00000040"
            style={StyleSheet.absoluteFill}
          >
            <View style={styles.childBox}>
              <Text style={styles.childText}>Child view above blur ✅</Text>
              <Switch
                value={showChildren}
                onValueChange={setShowChildren}
                thumbColor="#fff"
              />
            </View>
          </BlurView>
        </ImageBackground>
        <Text style={styles.note}>
          Expected: Text and Switch visible above blur on both platforms.
        </Text>
      </Section>

      {/* TEST 7: Edge cases */}
      <Section title="TEST 7 — Edge cases (should not crash)">
        <View style={styles.row}>
          <ImageBackground source={BG_IMAGE} style={styles.smallBox}>
            <BlurView blurAmount={0} style={StyleSheet.absoluteFill} />
            <Text style={styles.label}>amt=0</Text>
          </ImageBackground>

          <ImageBackground source={BG_IMAGE} style={styles.smallBox}>
            <BlurView style={StyleSheet.absoluteFill} />
            <Text style={styles.label}>defaults</Text>
          </ImageBackground>

          <ImageBackground source={BG_IMAGE} style={[styles.smallBox, { width: 30, height: 30 }]}>
            <BlurView blurAmount={10} style={StyleSheet.absoluteFill} />
            <Text style={[styles.label, { fontSize: 8 }]}>tiny</Text>
          </ImageBackground>
        </View>
        <Text style={styles.note}>
          Expected: No crash on any edge case.
        </Text>
      </Section>

    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f0f0f' },
  content: { padding: 16, paddingBottom: 60 },
  header: { fontSize: 22, fontWeight: '700', color: '#ffffff', marginTop: 48, marginBottom: 4 },
  subheader: { fontSize: 14, color: '#888', marginBottom: 4 },
  platform: { fontSize: 12, color: '#555', marginBottom: 24 },
  section: { marginBottom: 32 },
  sectionTitle: {
    fontSize: 13, fontWeight: '600', color: '#00D4AA',
    marginBottom: 12, textTransform: 'uppercase', letterSpacing: 0.5,
  },
  note: { fontSize: 11, color: '#666', marginTop: 8, fontStyle: 'italic', lineHeight: 16 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  smallBox: {
    width: 80, height: 80, borderRadius: 8, overflow: 'hidden',
    justifyContent: 'flex-end', alignItems: 'center', paddingBottom: 4,
  },
  tallBox: {
    height: 80, borderRadius: 8, overflow: 'hidden',
    marginBottom: 8, justifyContent: 'center', alignItems: 'center',
  },
  label: {
    color: '#fff', fontSize: 11, fontWeight: '600',
    textShadowColor: '#000', textShadowOffset: { width: 0, height: 1 }, textShadowRadius: 3,
  },
  overlayLabel: {
    color: '#fff', fontSize: 13, fontWeight: '600',
    textShadowColor: '#000', textShadowOffset: { width: 0, height: 1 }, textShadowRadius: 4,
  },
  overlayValue: {
    color: '#ddd', fontSize: 11,
    textShadowColor: '#000', textShadowOffset: { width: 0, height: 1 }, textShadowRadius: 3,
  },
  childBox: { alignItems: 'center', gap: 8 },
  childText: { color: '#fff', fontSize: 14, fontWeight: '600' },
});