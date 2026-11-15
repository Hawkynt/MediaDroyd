// The JNI surface of the text reader (native/src/reader.cpp) for com.hawkynt.mediadroyd.player.NativeText (#493): the
// same on Android, where the models come from the app's assets, and on the desktop, where they are files.
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "networks.h"
#include "platform.h"

namespace {

std::string text(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroyd_player_NativeText_open(JNIEnv* env, jobject, jobject assets, jstring dir, jboolean gpu) {
    auto reader = mediadroyd::openPpOcr(mediadroyd::platform::loader(env, assets, text(env, dir)), gpu);
    return reinterpret_cast<jlong>(reader.release());
}

// The lines in [grey] (w x h, a byte a pixel), flattened: for each line its x, y, width, height, the number of its
// ids and the ids. Null where the handle or the picture is missing.
JNIEXPORT jintArray JNICALL Java_com_hawkynt_mediadroyd_player_NativeText_read(
    JNIEnv* env, jobject, jlong handle, jbyteArray grey, jint w, jint h) {
    auto* reader = reinterpret_cast<mediadroyd::TextReader*>(handle);
    if (reader == nullptr || grey == nullptr || env->GetArrayLength(grey) < w * h) return nullptr;
    std::vector<unsigned char> pixels(static_cast<size_t>(w) * h);
    env->GetByteArrayRegion(grey, 0, w * h, reinterpret_cast<jbyte*>(pixels.data()));
    std::vector<jint> out;
    for (const auto& line : reader->read(pixels.data(), w, h)) {
        out.insert(out.end(), {line.x, line.y, line.width, line.height, static_cast<jint>(line.ids.size())});
        out.insert(out.end(), line.ids.begin(), line.ids.end());
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroyd_player_NativeText_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<mediadroyd::TextReader*>(handle);
}

}  // extern "C"
