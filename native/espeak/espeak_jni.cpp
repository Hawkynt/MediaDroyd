// espeak-ng's phonemes for the spoken subtitles' Piper voices (#531): NativeEspeak.phonemes, a clause at a time, in
// IPA as Piper's voices were trained on it (espeakPHONEMES_IPA). espeak-ng keeps one voice and state for the process,
// so the Kotlin side calls this from one thread at a time.
#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <string>

namespace {
std::string initialized;  // the data folder espeak-ng was set up with; empty before
std::string voiceSet;     // the voice set last

std::string utf8(JNIEnv* env, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hawkynt_mediadroyd_player_NativeEspeak_phonemes(
    JNIEnv* env, jclass, jstring dataDir, jstring voice, jstring text) {
    const std::string data = utf8(env, dataDir);
    if (initialized != data) {
        // The folder that holds espeak-ng-data, as espeak_Initialize takes it.
        if (espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data.c_str(), 0) < 0) return nullptr;
        initialized = data;
        voiceSet.clear();
    }
    const std::string name = utf8(env, voice);
    if (voiceSet != name) {
        if (espeak_SetVoiceByName(name.c_str()) != EE_OK) return nullptr;
        voiceSet = name;
    }
    const std::string words = utf8(env, text);
    const void* at = words.c_str();
    std::string phonemes;
    // espeak-ng hands the phonemes out a clause at a time until the text is used up.
    while (at != nullptr) {
        const char* clause = espeak_TextToPhonemes(&at, espeakCHARS_UTF8, espeakPHONEMES_IPA);
        if (clause == nullptr) break;
        if (!phonemes.empty()) phonemes += ' ';
        phonemes += clause;
    }
    return env->NewStringUTF(phonemes.c_str());
}
