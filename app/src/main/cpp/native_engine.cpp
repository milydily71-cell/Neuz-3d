#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_neuz3d_editor_MainActivity_nativeEngineInfo(JNIEnv* env, jobject) {
    const std::string info =
        "Neuz 3D native core: C++20 JNI loaded. Renderer selection and GPU "
        "feature checks are performed by the Android host; Vulkan backend is "
        "not yet initialized in this bootstrap milestone.";
    return env->NewStringUTF(info.c_str());
}
