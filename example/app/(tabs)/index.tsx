import React, { useState } from 'react'
import { Image, StyleSheet, Platform, Button, View, Alert } from 'react-native'

import { HelloWave } from '@/components/HelloWave'
import ParallaxScrollView from '@/components/ParallaxScrollView'
import { ThemedText } from '@/components/ThemedText'
import { ThemedView } from '@/components/ThemedView'
import { mediakit } from 'react-native-nitro-media-kit'

export default function HomeScreen() {
  const handleConvertImageToVideo = async () => {
    try {
      const video = await mediakit.convertImageToVideo(
        'https://unsplash.com/photos/b9-odQi5oDo/download?ixid=M3wxMjA3fDB8MXxzZWFyY2h8Mnx8dXJsfGVufDB8fHx8MTczMjM0MTM2NXww&force=true&w=1920',
        5
      )
      Alert.alert('Video Created', `Saved at: ${video}`)
      console.log(video, 'Video created using Nitro Media Kit')
    } catch (error) {
      console.error('Error converting image to video:', error)
      Alert.alert('Error', 'Failed to create video')
    }
  }

  const handleMergeVideos = async () => {
    try {
      const video = await mediakit.mergeVideos([
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4',
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
      ])
      console.log('Merged video saved at:', video)
    } catch (error) {
      console.error('Error merging videos:', error)
    }
  }

  const handleWatermark = async () => {
    try {
      const video = await mediakit.watermarkVideo(
        'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
        'Sameer Ather',
        'top-left'
      )
      console.log('Merged video saved at:', video)
    } catch (error) {
      console.error('Error merging videos:', error)
    }
  }

  return (
    <ParallaxScrollView
      headerBackgroundColor={{ light: '#A1CEDC', dark: '#1D3D47' }}
      headerImage={
        <Image
          source={require('@/assets/images/partial-react-logo.png')}
          style={styles.reactLogo}
        />
      }
    >
      <ThemedView style={styles.titleContainer}>
        <HelloWave />
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <Button
          title="Convert Image to Video"
          onPress={handleConvertImageToVideo}
        />
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <Button title="Merge Videos" onPress={handleMergeVideos} />
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <Button title="Watermark Video" onPress={handleWatermark} />
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <ThemedText type="subtitle">Step 1: Try it</ThemedText>
        <ThemedText>
          Edit{' '}
          <ThemedText type="defaultSemiBold">app/(tabs)/index.tsx</ThemedText>{' '}
          to see changes. Press{' '}
          <ThemedText type="defaultSemiBold">
            {Platform.select({
              ios: 'cmd + d',
              android: 'cmd + m',
              web: 'F12',
            })}
          </ThemedText>{' '}
          to open developer tools.
        </ThemedText>
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <ThemedText type="subtitle">Step 2: Explore</ThemedText>
        <ThemedText>
          Tap the Explore tab to learn more about what's included in this
          starter app.
        </ThemedText>
      </ThemedView>
      <ThemedView style={styles.stepContainer}>
        <ThemedText type="subtitle">Step 3: Get a fresh start</ThemedText>
        <ThemedText>
          When you're ready, run{' '}
          <ThemedText type="defaultSemiBold">npm run reset-project</ThemedText>{' '}
          to get a fresh <ThemedText type="defaultSemiBold">app</ThemedText>{' '}
          directory. This will move the current{' '}
          <ThemedText type="defaultSemiBold">app</ThemedText> to{' '}
          <ThemedText type="defaultSemiBold">app-example</ThemedText>.
        </ThemedText>
      </ThemedView>
    </ParallaxScrollView>
  )
}

const styles = StyleSheet.create({
  titleContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  stepContainer: {
    gap: 8,
    marginBottom: 8,
  },
  reactLogo: {
    height: 178,
    width: 290,
    bottom: 0,
    left: 0,
    position: 'absolute',
  },
})
