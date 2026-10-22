import { useRef, useState } from 'react';
import {
  Button,
  Image,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  compressMedia,
  convertImageToVideo,
  deleteGeneratedFile,
  extractThumbnail,
  getMediaInfo,
  mergeVideos,
  removeAudio,
  splitVideo,
  trimVideo,
  watermarkVideo,
} from 'react-native-nitro-media-kit';
import type { MediaInfoResult } from 'react-native-nitro-media-kit';

export default function App() {
  const [uri, setUri] = useState('');
  const [otherUri, setOtherUri] = useState('');
  const [text, setText] = useState('Nitro Media Kit');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(
    'Enter a local path, file:// URI or HTTP(S) URL. Android also supports content://.'
  );
  const [thumbnail, setThumbnail] = useState<string>();
  const generated = useRef(new Set<string>());

  async function run(action: () => Promise<MediaInfoResult>) {
    setBusy(true);
    try {
      const response = await action();
      setResult(JSON.stringify(response, null, 2));
      if (response.outputUri) generated.current.add(response.outputUri);
      response.segments?.forEach((path) => generated.current.add(path));
      if (response.ok && response.operation === 'extractThumbnail') {
        setThumbnail(`file://${response.outputUri}`);
      }
    } catch (error) {
      setResult(String(error));
    } finally {
      setBusy(false);
    }
  }

  async function cleanup() {
    setBusy(true);
    try {
      for (const path of generated.current) {
        await deleteGeneratedFile(path);
        generated.current.delete(path);
      }
      setThumbnail(undefined);
      setResult('Generated files deleted.');
    } catch (error) {
      setResult(String(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen}>
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.title}>Nitro Media Kit</Text>
        <Text>Media input</Text>
        <TextInput
          style={styles.input}
          value={uri}
          onChangeText={setUri}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="/path/to/media or https://…"
        />
        <Text>Second video for merging</Text>
        <TextInput
          style={styles.input}
          value={otherUri}
          onChangeText={setOtherUri}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="Second video URI"
        />
        <Text>Watermark text</Text>
        <TextInput style={styles.input} value={text} onChangeText={setText} />
        <View style={styles.actions}>
          <Button
            disabled={busy}
            title="Inspect media"
            onPress={() => run(() => getMediaInfo(uri))}
          />
          <Button
            disabled={busy}
            title="Compress media"
            onPress={() =>
              run(() => compressMedia(uri, { preset: 'balanced' }))
            }
          />
          <Button
            disabled={busy}
            title="Image → 2-second video"
            onPress={() => run(() => convertImageToVideo(uri, 2))}
          />
          <Button
            disabled={busy}
            title="Watermark video"
            onPress={() =>
              run(() =>
                watermarkVideo(uri, text, {
                  position: 'bottom-right',
                  opacity: 0.9,
                })
              )
            }
          />
          <Button
            disabled={busy}
            title="Merge two videos"
            onPress={() => run(() => mergeVideos([uri, otherUri]))}
          />
          <Button
            disabled={busy}
            title="Trim first second"
            onPress={() =>
              run(() => trimVideo(uri, { startMs: 0, endMs: 1000 }))
            }
          />
          <Button
            disabled={busy}
            title="Split first two seconds"
            onPress={() =>
              run(() =>
                splitVideo(uri, [
                  { startMs: 0, endMs: 1000 },
                  { startMs: 1000, endMs: 2000 },
                ])
              )
            }
          />
          <Button
            disabled={busy}
            title="Remove audio"
            onPress={() => run(() => removeAudio(uri))}
          />
          <Button
            disabled={busy}
            title="Extract thumbnail"
            onPress={() =>
              run(() => extractThumbnail(uri, { maxLongEdge: 640 }))
            }
          />
          <Button
            disabled={busy}
            title="Delete generated files"
            onPress={cleanup}
          />
        </View>
        {busy && <Text accessibilityLiveRegion="polite">Processing…</Text>}
        {thumbnail && (
          <Image
            source={{ uri: thumbnail }}
            style={styles.thumbnail}
            resizeMode="contain"
          />
        )}
        <Text selectable style={styles.result}>
          {result}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#fff' },
  content: { padding: 20, gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: '#111' },
  input: {
    borderWidth: 1,
    borderColor: '#aaa',
    borderRadius: 8,
    padding: 12,
    color: '#111',
  },
  actions: { gap: 8 },
  thumbnail: { width: '100%', height: 240 },
  result: { fontFamily: 'monospace', fontSize: 12, color: '#222' },
});
