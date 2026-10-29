#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <libretro.h>
#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex lock;
ANativeWindow* window = nullptr;
std::atomic<unsigned> held{0};
bool initialized = false, loaded = false;
retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
retro_system_av_info av{};
std::vector<uint8_t> rom;
std::vector<int16_t> pcm;
std::string savePath, directory;
uint64_t frameCount = 0, frameHash = 0, audioNonzero = 0;
unsigned videoWidth = 0, videoHeight = 0;

void logger(enum retro_log_level, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "SNESCore", fmt, args);
    va_end(args);
}
bool environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logger; return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto value = *static_cast<retro_pixel_format*>(data);
            if (value != RETRO_PIXEL_FORMAT_RGB565 && value != RETRO_PIXEL_FORMAT_0RGB1555 && value != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            pixelFormat = value; return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = directory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false; return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            static_cast<retro_variable*>(data)->value = nullptr; return false;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *static_cast<int*>(data) = 3; return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH; return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            return true;
        default: return false;
    }
}
uint32_t rgba(const uint8_t* row, unsigned x) {
    unsigned r, g, b;
    if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
        uint32_t p; std::memcpy(&p, row + x * 4, 4);
        r = (p >> 16) & 255; g = (p >> 8) & 255; b = p & 255;
    } else {
        uint16_t p; std::memcpy(&p, row + x * 2, 2);
        r = ((p >> (pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? 11 : 10)) & 31) * 255 / 31;
        g = pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? ((p >> 5) & 63) * 255 / 63 : ((p >> 5) & 31) * 255 / 31;
        b = (p & 31) * 255 / 31;
    }
    return 0xff000000u | (b << 16) | (g << 8) | r; // Android WINDOW_FORMAT_RGBA_8888, little endian
}
void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !width || !height || width > 1024 || height > 1024) return;
    videoWidth = width; videoHeight = height;
    uint64_t hash = 1469598103934665603ull;
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t bytesPerPixel = pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    for (unsigned y = 0; y < height; y += 3)
        for (unsigned x = 0; x < width * bytesPerPixel; x += bytesPerPixel * 3) {
            hash ^= bytes[y * pitch + x]; hash *= 1099511628211ull;
        }
    frameHash = hash;
    if (!window) return;
    if (ANativeWindow_getWidth(window) != static_cast<int>(width) || ANativeWindow_getHeight(window) != static_cast<int>(height))
        ANativeWindow_setBuffersGeometry(window, static_cast<int>(width), static_cast<int>(height), WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer target{};
    if (ANativeWindow_lock(window, &target, nullptr) != 0) return;
    unsigned rows = std::min(height, static_cast<unsigned>(target.height));
    unsigned cols = std::min(width, static_cast<unsigned>(target.width));
    auto* dest = static_cast<uint32_t*>(target.bits);
    for (unsigned y = 0; y < rows; ++y)
        for (unsigned x = 0; x < cols; ++x) dest[y * target.stride + x] = rgba(bytes + y * pitch, x);
    ANativeWindow_unlockAndPost(window);
}
size_t audio(const int16_t* data, size_t frames) {
    if (frames > 32768 || pcm.size() + frames * 2 > 65536) return frames;
    pcm.insert(pcm.end(), data, data + frames * 2);
    for (size_t i = 0; i < frames * 2; ++i) if (data[i]) ++audioNonzero;
    return frames;
}
void sample(int16_t left, int16_t right) { int16_t data[]{left, right}; audio(data, 1); }
void poll() {}
int16_t input(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    unsigned mask = held.load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(mask);
    return id < 16 && (mask & (1u << id)) ? 1 : 0;
}
void saveRam() {
    if (!loaded || savePath.empty()) return;
    auto* memory = static_cast<const char*>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!memory || !size || size > 1024 * 1024) return;
    std::string temporary = savePath + ".partial";
    std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
    out.write(memory, static_cast<std::streamsize>(size)); out.close();
    if (out) std::rename(temporary.c_str(), savePath.c_str());
}
void unload() {
    if (loaded) { saveRam(); retro_unload_game(); loaded = false; }
    if (initialized) { retro_deinit(); initialized = false; }
    held.store(0); rom.clear(); pcm.clear();
    frameCount = frameHash = audioNonzero = 0; videoWidth = videoHeight = 0;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrosearch_play_NativeCore_load(JNIEnv* env, jclass, jbyteArray data, jstring path) {
    std::lock_guard<std::mutex> guard(lock);
    unload();
    if (!data || !path) return false;
    jsize length = env->GetArrayLength(data);
    if (length < 32768 || length > 16 * 1024 * 1024) return false;
    const char* text = env->GetStringUTFChars(path, nullptr);
    if (!text) return false;
    savePath = text; env->ReleaseStringUTFChars(path, text);
    directory = savePath.substr(0, savePath.find_last_of('/'));
    rom.resize(length); env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(rom.data()));
    if (env->ExceptionCheck()) { rom.clear(); return false; }
    frameCount = frameHash = audioNonzero = 0; videoWidth = videoHeight = 0;
    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    retro_set_environment(environment); retro_set_video_refresh(video);
    retro_set_audio_sample(sample); retro_set_audio_sample_batch(audio);
    retro_set_input_poll(poll); retro_set_input_state(input);
    retro_init(); initialized = true;
    retro_game_info info{}; info.data = rom.data(); info.size = rom.size();
    info.path = "game.sfc";
    loaded = retro_load_game(&info);
    if (!loaded) { unload(); return false; }
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_get_system_av_info(&av);
    if (av.timing.fps < 40 || av.timing.fps > 70 || av.timing.sample_rate < 8000 || av.timing.sample_rate > 96000) {
        unload(); return false;
    }
    auto* memory = static_cast<char*>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (memory && size > 0 && size <= 1024 * 1024) {
        std::ifstream in(savePath, std::ios::binary); if (in) in.read(memory, static_cast<std::streamsize>(size));
    }
    return true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_retrosearch_play_NativeCore_surface(JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> guard(lock);
    if (window) { ANativeWindow_release(window); window = nullptr; }
    if (surface) window = ANativeWindow_fromSurface(env, surface);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_retrosearch_play_NativeCore_frame(JNIEnv* env, jclass, jshortArray output) {
    std::lock_guard<std::mutex> guard(lock);
    if (!loaded || !output) return 0;
    pcm.clear(); retro_run(); frameCount++;
    size_t count = std::min(pcm.size(), static_cast<size_t>(env->GetArrayLength(output)));
    if (count) env->SetShortArrayRegion(output, 0, static_cast<jsize>(count), pcm.data());
    return static_cast<jint>(count);
}
extern "C" JNIEXPORT void JNICALL
Java_com_retrosearch_play_NativeCore_buttons(JNIEnv*, jclass, jint mask) { held.store(static_cast<unsigned>(mask) & 4095u); }
extern "C" JNIEXPORT jdouble JNICALL
Java_com_retrosearch_play_NativeCore_fps(JNIEnv*, jclass) { std::lock_guard<std::mutex> guard(lock); return av.timing.fps; }
extern "C" JNIEXPORT jint JNICALL
Java_com_retrosearch_play_NativeCore_sampleRate(JNIEnv*, jclass) { std::lock_guard<std::mutex> guard(lock); return static_cast<jint>(av.timing.sample_rate); }
extern "C" JNIEXPORT void JNICALL
Java_com_retrosearch_play_NativeCore_save(JNIEnv*, jclass) { std::lock_guard<std::mutex> guard(lock); saveRam(); }
extern "C" JNIEXPORT void JNICALL
Java_com_retrosearch_play_NativeCore_close(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(lock); unload();
    if (window) { ANativeWindow_release(window); window = nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_retrosearch_play_NativeCore_stats(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    jlong values[]{static_cast<jlong>(frameCount), static_cast<jlong>(frameHash), videoWidth, videoHeight, static_cast<jlong>(audioNonzero)};
    auto result = env->NewLongArray(5); env->SetLongArrayRegion(result, 0, 5, values); return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_retrosearch_play_NativeCore_sram(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    if (!loaded) return env->NewByteArray(0);
    auto* memory = static_cast<jbyte*>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!memory || size > 1024 * 1024) return env->NewByteArray(0);
    auto result = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), memory); return result;
}
