import { NitroModules } from 'react-native-nitro-modules';
import type { NitroMediaKit as NativeMediaKit } from './NitroMediaKit.nitro';

export const NitroMediaKit =
  NitroModules.createHybridObject<NativeMediaKit>('NitroMediaKit');
