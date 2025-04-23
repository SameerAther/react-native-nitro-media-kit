import { NitroModules } from 'react-native-nitro-modules';
import type { NitroMediaKit } from './NitroMediaKit.nitro';

const NitroMediaKitHybridObject =
  NitroModules.createHybridObject<NitroMediaKit>('NitroMediaKit');

export function multiply(a: number, b: number): number {
  return NitroMediaKitHybridObject.multiply(a, b);
}
