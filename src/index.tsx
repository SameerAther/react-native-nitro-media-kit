import { NitroModules } from 'react-native-nitro-modules';
import type { NitroMediaKit as NitroMediaKitObject } from './NitroMediaKit.nitro';

export const NitroMediaKit =
  NitroModules.createHybridObject<NitroMediaKitObject>('NitroMediaKit');
