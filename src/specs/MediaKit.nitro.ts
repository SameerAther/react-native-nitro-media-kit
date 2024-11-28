import type { HybridObject } from 'react-native-nitro-modules'

export interface MediaKit
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  convertImageToVideo(image: string, duration: number): Promise<string>
  mergeVideos(videos: string[]): Promise<string>
  watermarkVideo(
    video: string,
    watermark: string,
    position: string
  ): Promise<string>
}
