import { NitroModules } from 'react-native-nitro-modules'
import type { MediaKit } from './specs/MediaKit.nitro'

export const mediakit = NitroModules.createHybridObject<MediaKit>('MediaKit')
