import { View, StyleSheet, Alert, Button } from 'react-native';
import { NitroMediaKit } from 'react-native-nitro-media-kit';

export default function App() {
  const handleConvertImageToVideo = async () => {
    try {
      const video = await NitroMediaKit.convertImageToVideo(
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
      const video = await NitroMediaKit.mergeVideos([
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4',
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
      ]);
      Alert.alert('Merged Video Created', `Saved at: ${video}`);
      console.log('Merged video saved at:', video);
    } catch (error) {
      console.error('Error merging videos:', error);
    }
  };

  const handleWatermark = async () => {
    try {
      const video = await NitroMediaKit.watermarkVideo(
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
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
