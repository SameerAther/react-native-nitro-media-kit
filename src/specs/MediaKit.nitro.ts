import type { HybridObject } from 'react-native-nitro-modules'

export interface MediaKit
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  readonly pi: number
  add(a: number, b: number): number
  convertImageToVideo(image: string, duration: number): Promise<string>
  mergeVideos(videos: string[]): Promise<string>
}
