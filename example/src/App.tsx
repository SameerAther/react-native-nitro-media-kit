import { View, StyleSheet, Alert, Button } from 'react-native';
import {
  convertImageToVideo,
  mergeVideos,
  watermarkVideo,
} from 'react-native-nitro-media-kit';

export default function App() {
  const handleConvertImageToVideo = async () => {
    try {
      const video = await convertImageToVideo(
        'https://unsplash.com/photos/b9-odQi5oDo/download?ixid=M3wxMjA3fDB8MXxzZWFyY2h8Mnx8dXJsfGVufDB8fHx8MTczMjM0MTM2NXww&force=true&w=1920',
        5
      );
      Alert.alert('Video Created', `Saved at: ${video}`);
      console.log(video, 'Video created using Nitro Media Kit');
    } catch (error) {
      console.error('Error converting image to video:', error);
      Alert.alert('Error', 'Failed to create video');
    }
  };

  const handleMergeVideos = async () => {
    try {
      const video = await mergeVideos([
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
      ]);
      Alert.alert('Merged Video Created', `Saved at: ${video}`);
      console.log('Merged video saved at:', video);
    } catch (error) {
      console.error('Error merging videos:', error);
    }
  };

  const handleWatermark = async () => {
    try {
      const video = await watermarkVideo(
        'https://www.pexels.com/download/video/4114797/?fps=25.0&h=240&w=426',
        'Sameer Ather',
        'bottom-right'
      );
      Alert.alert('Watermarked Video Created', `Saved at: ${video}`);
      console.log('Watermarked video saved at:', video);
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
