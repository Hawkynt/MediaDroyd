// Subtitles made from the speech (#529): whisper.cpp's Whisper over a stretch of the film's sound (16 kHz mono floats),
// NativeWhisper in Kotlin. A context belongs to one caller at a time.
#include <jni.h>
#include <whisper.h>
#include <string>

extern "C" JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroyd_player_NativeWhisper_open(JNIEnv* env, jclass, jstring model) {
    const char* path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroyd_player_NativeWhisper_close(JNIEnv*, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context*>(handle));
}

// The segments of [samples] as one string: the language first ("en"), then a line per segment — its start and end in
// milliseconds and its text, tab-separated; null where Whisper failed.
extern "C" JNIEXPORT jstring JNICALL
Java_com_hawkynt_mediadroyd_player_NativeWhisper_transcribe(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples, jstring language, jboolean translate, jint threads) {
    auto* context = reinterpret_cast<whisper_context*>(handle);
    if (context == nullptr) return nullptr;
    const char* lang = env->GetStringUTFChars(language, nullptr);
    std::string chosen(lang ? lang : "auto");
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = translate;
    params.language = chosen.c_str();
    params.detect_language = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_nst = true;

    const jsize count = env->GetArrayLength(samples);
    jfloat* pcm = env->GetFloatArrayElements(samples, nullptr);
    const int failed = whisper_full(context, params, pcm, count);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (failed != 0) return nullptr;

    std::string out = whisper_lang_str(whisper_full_lang_id(context));
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; i++) {
        // Whisper counts in hundredths of a second.
        const long long t0 = whisper_full_get_segment_t0(context, i) * 10;
        const long long t1 = whisper_full_get_segment_t1(context, i) * 10;
        std::string text = whisper_full_get_segment_text(context, i);
        for (char& c : text) if (c == '\t' || c == '\n') c = ' ';
        out += "\n" + std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text;
    }
    return env->NewStringUTF(out.c_str());
}
