#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Whisper calls this between inference steps. Cancellation interrupts the Kotlin worker thread.
struct Cancellation {
    JavaVM *vm;
    jobject thread;
    jmethodID interrupted;
};
static bool cancelled(void *arg) {
    const auto &state = *static_cast<Cancellation *>(arg);
    JNIEnv *env = nullptr;
    bool attached = state.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && state.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
    bool stop = env->CallBooleanMethod(state.thread, state.interrupted);
    if (attached) state.vm->DetachCurrentThread();
    return stop;
}

struct Free { void operator()(whisper_context *c) const { whisper_free(c); } };
static std::mutex gate;
static std::unique_ptr<whisper_context, Free> loaded;
static std::string loadedPath;

extern "C" JNIEXPORT void JNICALL
Java_dev_kento_data_media_OfflineSpeech_release(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(gate);
    loaded.reset();
    loadedPath.clear();
}

// Return UTF-8 bytes. NewStringUTF expects modified UTF-8 and corrupts supplementary characters.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_kento_data_media_OfflineSpeech_recognize(JNIEnv *env, jobject, jstring path, jfloatArray pcm) {
    const char *filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return nullptr;
    std::lock_guard<std::mutex> guard(gate);
    // Loading the model takes seconds; phrases and dictation reuse it until release().
    if (!loaded || loadedPath != filename) {
        loaded.reset();
        auto options = whisper_context_default_params();
        options.use_gpu = false;
        loaded.reset(whisper_init_from_file_with_params(filename, options));
        loadedPath = loaded ? filename : "";
    }
    env->ReleaseStringUTFChars(path, filename);
    whisper_context *ctx = loaded.get();
    if (!ctx) return nullptr;
    std::vector<float> samples(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, samples.size(), samples.data());
    auto p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.language = "auto";
    p.translate = false;
    p.no_timestamps = true;
    p.print_progress = p.print_realtime = p.print_timestamps = p.print_special = false;
    // Leave one core to the UI: ggml workers spin while waiting for each other and stall badly when the
    // render thread takes one of their cores.
    p.n_threads = static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 2u, 5u) - 1);
    p.beam_search.beam_size = 3;
    // The encoder pads every call to a 30 s window (1500 frames, 50 per second). Short phrases use a smaller window,
    // but never below 15 s: shorter contexts made the model repeat and bend Russian word endings.
    p.audio_ctx = std::min(whisper_model_n_audio_ctx(ctx), std::max(750, static_cast<int>(samples.size() / 320) + 100));
    const auto threadClass = env->FindClass("java/lang/Thread");
    const auto current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    Cancellation cancellation{};
    env->GetJavaVM(&cancellation.vm);
    cancellation.thread = env->NewGlobalRef(env->CallStaticObjectMethod(threadClass, current));
    cancellation.interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
    p.abort_callback = cancelled;
    p.abort_callback_user_data = &cancellation;
    const int status = whisper_full(ctx, p, samples.data(), samples.size());
    env->DeleteGlobalRef(cancellation.thread);
    if (status != 0) return nullptr;
    std::string result;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i)
        result += whisper_full_get_segment_text(ctx, i);
    auto out = env->NewByteArray(result.size());
    if (out) env->SetByteArrayRegion(out, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
    return out;
}
