#include <jni.h>
#include "MediaKitOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::mediakit::initialize(vm);
}
