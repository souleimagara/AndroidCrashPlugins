/**
 * Native Crash Handler for Android
 * Captures native crashes (SIGSEGV, SIGABRT, etc.) at the signal level
 */

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <sys/types.h>
#include <fcntl.h>
#include <cstring>
#include <ctime>
#include <cstdio>
#include <cstdlib>
#include <pthread.h>
#include <android/log.h>
#include <unwind.h>
#include <dlfcn.h>

#define LOG_TAG "NativeCrashHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Maximum stack frames to capture
#define MAX_STACK_FRAMES 64

// Structure to hold crash information
struct CrashInfo {
    int signal;
    int code;
    void* fault_address;
    char signal_name[32];
    char thread_name[128];
    pid_t pid;
    pid_t tid;
    time_t crash_time;
    uintptr_t stack_frames[MAX_STACK_FRAMES];
    size_t frame_count;
};

// Global storage for crash info (must be signal-safe)
static CrashInfo g_crash_info;
static char g_crash_file_path[256];
static struct sigaction g_old_handlers[32];
static bool g_initialized = false;

// Signal names for better error reporting
static const char* get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGBUS:  return "SIGBUS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

// Get signal description
static const char* get_signal_description(int sig) {
    switch (sig) {
        case SIGSEGV: return "Segmentation fault (invalid memory access)";
        case SIGABRT: return "Abort signal (abnormal termination)";
        case SIGFPE:  return "Floating point exception";
        case SIGILL:  return "Illegal instruction";
        case SIGBUS:  return "Bus error (invalid memory alignment)";
        case SIGTRAP: return "Trace/breakpoint trap";
        default:      return "Unknown signal";
    }
}

// Unwind callback structure
struct UnwindState {
    uintptr_t* frames;
    size_t frame_count;
    size_t max_frames;
};

// Callback for stack unwinding
static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    UnwindState* state = static_cast<UnwindState*>(arg);

    if (state->frame_count >= state->max_frames) {
        return _URC_END_OF_STACK;
    }

    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        state->frames[state->frame_count++] = pc;
    }

    return _URC_NO_REASON;
}

// Capture stack trace (async-signal-safe)
static size_t capture_stack_trace(uintptr_t* frames, size_t max_frames) {
    UnwindState state;
    state.frames = frames;
    state.frame_count = 0;
    state.max_frames = max_frames;

    _Unwind_Backtrace(unwind_callback, &state);

    return state.frame_count;
}

// Get thread name (async-signal-safe)
static void get_thread_name(char* buffer, size_t size) {
#if __ANDROID_API__ >= 26
    pthread_t thread = pthread_self();
    if (pthread_getname_np(thread, buffer, size) != 0) {
        snprintf(buffer, size, "Thread-%d", (int)gettid());
    }
#else
    // pthread_getname_np requires Android API 26+
    snprintf(buffer, size, "Thread-%d", (int)gettid());
#endif
}

// Write crash info to file (async-signal-safe operations only!)
static void write_crash_to_file(const CrashInfo* info) {
    // Open file for writing (use creat for async-signal-safety)
    int fd = open(g_crash_file_path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }

    char buffer[512];
    int len;

    // Write header
    len = snprintf(buffer, sizeof(buffer),
                   "NATIVE_CRASH\n"
                   "Signal: %s (%d)\n"
                   "Description: %s\n"
                   "Code: %d\n"
                   "Fault Address: %p\n"
                   "Thread: %s\n"
                   "PID: %d\n"
                   "TID: %d\n"
                   "Time: %ld\n"
                   "Frame Count: %zu\n",
                   info->signal_name,
                   info->signal,
                   get_signal_description(info->signal),
                   info->code,
                   info->fault_address,
                   info->thread_name,
                   info->pid,
                   info->tid,
                   info->crash_time,
                   info->frame_count);
    write(fd, buffer, len);

    // Write stack frames
    write(fd, "Stack Trace:\n", 13);
    for (size_t i = 0; i < info->frame_count; i++) {
        Dl_info dl_info;
        if (dladdr((void*)info->stack_frames[i], &dl_info)) {
            len = snprintf(buffer, sizeof(buffer),
                          "#%02zu pc %p %s (%s+%p)\n",
                          i,
                          (void*)info->stack_frames[i],
                          dl_info.dli_fname ? dl_info.dli_fname : "???",
                          dl_info.dli_sname ? dl_info.dli_sname : "???",
                          (void*)((char*)info->stack_frames[i] - (char*)dl_info.dli_saddr));
        } else {
            len = snprintf(buffer, sizeof(buffer),
                          "#%02zu pc %p ???\n",
                          i,
                          (void*)info->stack_frames[i]);
        }
        write(fd, buffer, len);
    }

    close(fd);

    LOGI("Native crash info written to: %s", g_crash_file_path);
}

// Signal handler (MUST be async-signal-safe!)
static void signal_handler(int sig, siginfo_t* info, void* context) {
    // Prevent recursive crashes
    static volatile sig_atomic_t handling_crash = 0;
    if (handling_crash) {
        _exit(1);
    }
    handling_crash = 1;

    // Collect crash information
    memset(&g_crash_info, 0, sizeof(g_crash_info));
    g_crash_info.signal = sig;
    g_crash_info.code = info->si_code;
    g_crash_info.fault_address = info->si_addr;
    g_crash_info.pid = getpid();
    g_crash_info.tid = gettid();
    g_crash_info.crash_time = time(nullptr);

    strncpy(g_crash_info.signal_name, get_signal_name(sig), sizeof(g_crash_info.signal_name) - 1);
    get_thread_name(g_crash_info.thread_name, sizeof(g_crash_info.thread_name));

    // Capture stack trace
    g_crash_info.frame_count = capture_stack_trace(g_crash_info.stack_frames, MAX_STACK_FRAMES);

    // Write crash info to file
    write_crash_to_file(&g_crash_info);

    // Call original handler (if any)
    struct sigaction* old_handler = &g_old_handlers[sig];
    if (old_handler->sa_sigaction) {
        old_handler->sa_sigaction(sig, info, context);
    } else if (old_handler->sa_handler && old_handler->sa_handler != SIG_DFL && old_handler->sa_handler != SIG_IGN) {
        old_handler->sa_handler(sig);
    }

    // Re-raise signal with default handler
    signal(sig, SIG_DFL);
    raise(sig);
}

// Initialize native crash handler
extern "C" JNIEXPORT void JNICALL
Java_com_crashreporter_library_NativeCrashHandler_initialize(JNIEnv* env, jobject /* this */, jstring crash_dir) {
    if (g_initialized) {
        LOGD("Native crash handler already initialized");
        return;
    }

    // Get crash directory path
    const char* crash_dir_str = env->GetStringUTFChars(crash_dir, nullptr);
    snprintf(g_crash_file_path, sizeof(g_crash_file_path), "%s/native_crash.txt", crash_dir_str);
    env->ReleaseStringUTFChars(crash_dir, crash_dir_str);

    LOGI("Initializing native crash handler, crash file: %s", g_crash_file_path);

    // Set up signal handlers
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    // Install handlers for critical signals
    sigaction(SIGSEGV, &sa, &g_old_handlers[SIGSEGV]);
    sigaction(SIGABRT, &sa, &g_old_handlers[SIGABRT]);
    sigaction(SIGFPE,  &sa, &g_old_handlers[SIGFPE]);
    sigaction(SIGILL,  &sa, &g_old_handlers[SIGILL]);
    sigaction(SIGBUS,  &sa, &g_old_handlers[SIGBUS]);
    sigaction(SIGTRAP, &sa, &g_old_handlers[SIGTRAP]);

    g_initialized = true;
    LOGI("Native crash handler initialized successfully");
}

// Test method to trigger a native crash (for testing purposes)
extern "C" JNIEXPORT void JNICALL
Java_com_crashreporter_library_NativeCrashHandler_triggerNativeCrash(JNIEnv* env, jobject /* this */, jint type) {
    LOGD("Triggering native crash type: %d", type);

    switch (type) {
        case 0: // SIGSEGV - Null pointer dereference
            *((volatile int*)nullptr) = 42;
            break;

        case 1: // SIGABRT - Abort
            abort();
            break;

        case 2: // SIGFPE - Division by zero
            {
                volatile int zero = 0;
                volatile int result = 42 / zero;
                (void)result;
            }
            break;

        case 3: // SIGSEGV - Invalid memory access
            {
                volatile char* bad_ptr = (char*)0xDEADBEEF;
                *bad_ptr = 'x';
            }
            break;

        case 4: // Stack overflow
            Java_com_crashreporter_library_NativeCrashHandler_triggerNativeCrash(env, nullptr, 4);
            break;

        default:
            LOGE("Unknown crash type: %d", type);
            break;
    }
}

// Get initialization status
extern "C" JNIEXPORT jboolean JNICALL
Java_com_crashreporter_library_NativeCrashHandler_isInitialized(JNIEnv* env, jobject /* this */) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}
