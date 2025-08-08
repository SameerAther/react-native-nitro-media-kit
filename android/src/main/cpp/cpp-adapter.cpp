#include <jni.h>
#include "nitromediakitOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::nitromediakit::initialize(vm);
}
