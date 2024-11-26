import type { HybridObject } from 'react-native-nitro-modules'

export interface MediaKit
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  readonly pi: number
  convertImageToVideo(image: string, duration: number): Promise<string>
  mergeVideos(videos: string[]): Promise<string>
}
