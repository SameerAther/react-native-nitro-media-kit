import { View, StyleSheet, Alert, Button } from 'react-native';
import {
  convertImageToVideo,
  getMediaInfo,
  mergeVideos,
  watermarkVideo,
} from 'react-native-nitro-media-kit';

export default function App() {
  const handleConvertImageToVideo = async () => {
    try {
      const result = await convertImageToVideo(
        'https://unsplash.com/photos/b9-odQi5oDo/download?ixid=M3wxMjA3fDB8MXxzZWFyY2h8Mnx8dXJsfGVufDB8fHx8MTczMjM0MTM2NXww&force=true&w=1920',
        5
      );
      if (!result.ok) {
        Alert.alert('Error', result.error?.message ?? 'Failed to create video');
        return;
      }
      Alert.alert('Video Created', `Saved at: ${result.outputUri}`);
      console.log(result.outputUri, 'Video created using Nitro Media Kit');
    } catch (error) {
      console.error('Error converting image to video:', error);
      Alert.alert('Error', 'Failed to create video');
    }
  };

  const handleMergeVideos = async () => {
    try {
      const result = await mergeVideos([
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
      ]);
      console.log('Merge result:', result);
      if (!result.ok) {
        Alert.alert('Error', result.error?.message ?? 'Failed to merge videos');
        return;
      }
      Alert.alert('Merged Video Created', `Saved at: ${result.outputUri}`);
      console.log('Merged video saved at:', result.outputUri);
    } catch (error) {
      console.error('Error merging videos:', error);
    }
  };

  const handleWatermark = async () => {
    try {
      const result = await watermarkVideo(
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'Sameer Ather',
        'bottom-right'
      );
      if (!result.ok) {
        Alert.alert(
          'Error',
          result.error?.message ?? 'Failed to watermark video'
        );
        return;
      }
      Alert.alert('Watermarked Video Created', `Saved at: ${result.outputUri}`);
      console.log('Watermarked video saved at:', result.outputUri);
    } catch (error) {
      console.error('Error watermarking video:', error);
    }
  };
  return (
    <View style={styles.container}>
      <Button
        title="Convert Image to Video"
        onPress={handleConvertImageToVideo}
      />
      <Button title="Merge Videos" onPress={handleMergeVideos} />
      <Button title="Watermark Video" onPress={handleWatermark} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
