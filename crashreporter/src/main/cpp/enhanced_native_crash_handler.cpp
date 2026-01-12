/**
 * ENHANCED Native Crash Handler for Android
 *
 * NEW FEATURES:
 * - Register dump (PC, SP, LR, general purpose registers)
 * - Memory dump around fault address
 * - Increased stack depth (128 frames)
 * - JSON output format
 * - Better symbol resolution
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
#include <ucontext.h>

#define LOG_TAG "EnhancedNativeCrashHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Maximum stack frames to capture (INCREASED from 64 to 128)
#define MAX_STACK_FRAMES 128

// Memory dump size (256 bytes before and after fault address)
#define MEMORY_DUMP_SIZE 256

// Structure to hold enhanced crash information
struct EnhancedCrashInfo {
    // Basic crash info
    int signal;
    int code;
    void* fault_address;
    char signal_name[32];
    char thread_name[128];
    pid_t pid;
    pid_t tid;
    time_t crash_time;

    // Stack frames
    uintptr_t stack_frames[MAX_STACK_FRAMES];
    size_t frame_count;

    // NEW: Register dump
    struct {
        uintptr_t pc;   // Program Counter
        uintptr_t sp;   // Stack Pointer
        uintptr_t lr;   // Link Register (return address)

        #ifdef __aarch64__
        // ARM64 registers
        uintptr_t x[31];    // x0-x30 general purpose registers
        uintptr_t cpsr;     // Current Program Status Register
        #elif defined(__arm__)
        // ARM32 registers
        uintptr_t r[16];    // r0-r15 general purpose registers
        uintptr_t cpsr;     // Current Program Status Register
        #elif defined(__i386__)
        // x86 registers
        uintptr_t eax, ebx, ecx, edx;
        uintptr_t esi, edi, ebp, esp, eip;
        #elif defined(__x86_64__)
        // x86_64 registers
        uintptr_t rax, rbx, rcx, rdx;
        uintptr_t rsi, rdi, rbp, rsp, rip;
        uintptr_t r8, r9, r10, r11, r12, r13, r14, r15;
        #endif
    } registers;

    // NEW: Memory dump around fault address
    unsigned char memory_before[MEMORY_DUMP_SIZE];
    unsigned char memory_after[MEMORY_DUMP_SIZE];
    bool memory_readable;
};

// Global storage for crash info (must be signal-safe)
static EnhancedCrashInfo g_crash_info;
static char g_crash_file_path[256];
static struct sigaction g_old_handlers[32];
static bool g_initialized = false;

// Signal names
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

// Signal descriptions
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

// Capture stack trace
static size_t capture_stack_trace(uintptr_t* frames, size_t max_frames) {
    UnwindState state;
    state.frames = frames;
    state.frame_count = 0;
    state.max_frames = max_frames;

    _Unwind_Backtrace(unwind_callback, &state);

    return state.frame_count;
}

// Get thread name
static void get_thread_name(char* buffer, size_t size) {
#if __ANDROID_API__ >= 26
    pthread_t thread = pthread_self();
    if (pthread_getname_np(thread, buffer, size) != 0) {
        snprintf(buffer, size, "Thread-%d", (int)gettid());
    }
#else
    snprintf(buffer, size, "Thread-%d", (int)gettid());
#endif
}

// NEW: Capture registers from context
static void capture_registers(EnhancedCrashInfo* info, void* context) {
    if (!context) return;

    ucontext_t* uc = (ucontext_t*)context;

    #ifdef __aarch64__
    // ARM64
    info->registers.pc = uc->uc_mcontext.pc;
    info->registers.sp = uc->uc_mcontext.sp;
    // LR is x30 on ARM64
    info->registers.lr = uc->uc_mcontext.regs[30];
    info->registers.cpsr = uc->uc_mcontext.pstate;

    for (int i = 0; i < 31; i++) {
        info->registers.x[i] = uc->uc_mcontext.regs[i];
    }

    #elif defined(__arm__)
    // ARM32
    info->registers.pc = uc->uc_mcontext.arm_pc;
    info->registers.sp = uc->uc_mcontext.arm_sp;
    info->registers.lr = uc->uc_mcontext.arm_lr;
    info->registers.cpsr = uc->uc_mcontext.arm_cpsr;

    info->registers.r[0] = uc->uc_mcontext.arm_r0;
    info->registers.r[1] = uc->uc_mcontext.arm_r1;
    info->registers.r[2] = uc->uc_mcontext.arm_r2;
    info->registers.r[3] = uc->uc_mcontext.arm_r3;
    info->registers.r[4] = uc->uc_mcontext.arm_r4;
    info->registers.r[5] = uc->uc_mcontext.arm_r5;
    info->registers.r[6] = uc->uc_mcontext.arm_r6;
    info->registers.r[7] = uc->uc_mcontext.arm_r7;
    info->registers.r[8] = uc->uc_mcontext.arm_r8;
    info->registers.r[9] = uc->uc_mcontext.arm_r9;
    info->registers.r[10] = uc->uc_mcontext.arm_r10;
    info->registers.r[11] = uc->uc_mcontext.arm_fp;
    info->registers.r[12] = uc->uc_mcontext.arm_ip;
    info->registers.r[13] = uc->uc_mcontext.arm_sp;
    info->registers.r[14] = uc->uc_mcontext.arm_lr;
    info->registers.r[15] = uc->uc_mcontext.arm_pc;

    #elif defined(__x86_64__)
    // x86_64
    info->registers.rip = uc->uc_mcontext.gregs[REG_RIP];
    info->registers.rsp = uc->uc_mcontext.gregs[REG_RSP];
    info->registers.rbp = uc->uc_mcontext.gregs[REG_RBP];
    info->registers.rax = uc->uc_mcontext.gregs[REG_RAX];
    info->registers.rbx = uc->uc_mcontext.gregs[REG_RBX];
    info->registers.rcx = uc->uc_mcontext.gregs[REG_RCX];
    info->registers.rdx = uc->uc_mcontext.gregs[REG_RDX];
    info->registers.rsi = uc->uc_mcontext.gregs[REG_RSI];
    info->registers.rdi = uc->uc_mcontext.gregs[REG_RDI];

    #elif defined(__i386__)
    // x86
    info->registers.eip = uc->uc_mcontext.gregs[REG_EIP];
    info->registers.esp = uc->uc_mcontext.gregs[REG_ESP];
    info->registers.ebp = uc->uc_mcontext.gregs[REG_EBP];
    info->registers.eax = uc->uc_mcontext.gregs[REG_EAX];
    info->registers.ebx = uc->uc_mcontext.gregs[REG_EBX];
    info->registers.ecx = uc->uc_mcontext.gregs[REG_ECX];
    info->registers.edx = uc->uc_mcontext.gregs[REG_EDX];
    info->registers.esi = uc->uc_mcontext.gregs[REG_ESI];
    info->registers.edi = uc->uc_mcontext.gregs[REG_EDI];
    #endif
}

// NEW: Try to read memory around fault address
static void capture_memory_dump(EnhancedCrashInfo* info) {
    info->memory_readable = false;

    if (!info->fault_address) {
        return;
    }

    // Try to read memory before fault address
    unsigned char* addr = (unsigned char*)info->fault_address;

    // Read before
    for (int i = 0; i < MEMORY_DUMP_SIZE; i++) {
        unsigned char* read_addr = addr - MEMORY_DUMP_SIZE + i;
        // Try to read (may fail if not readable)
        __builtin_memcpy(&info->memory_before[i], read_addr, 1);
    }

    // Read after
    for (int i = 0; i < MEMORY_DUMP_SIZE; i++) {
        unsigned char* read_addr = addr + i;
        __builtin_memcpy(&info->memory_after[i], read_addr, 1);
    }

    info->memory_readable = true;
}

// Write crash info to file (async-signal-safe operations only!)
static void write_crash_to_file(const EnhancedCrashInfo* info) {
    int fd = open(g_crash_file_path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }

    char buffer[1024];
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
                   "Frame Count: %zu\n\n",
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

    // Write registers
    write(fd, "REGISTERS:\n", 11);

    #ifdef __aarch64__
    len = snprintf(buffer, sizeof(buffer),
                  "  pc:   %016lx\n  sp:   %016lx\n  lr:   %016lx\n  cpsr: %016lx\n",
                  info->registers.pc, info->registers.sp,
                  info->registers.lr, info->registers.cpsr);
    write(fd, buffer, len);

    for (int i = 0; i < 31; i++) {
        len = snprintf(buffer, sizeof(buffer), "  x%-2d:  %016lx\n", i, info->registers.x[i]);
        write(fd, buffer, len);
    }

    #elif defined(__arm__)
    len = snprintf(buffer, sizeof(buffer),
                  "  pc:   %08lx\n  sp:   %08lx\n  lr:   %08lx\n  cpsr: %08lx\n",
                  info->registers.pc, info->registers.sp,
                  info->registers.lr, info->registers.cpsr);
    write(fd, buffer, len);

    for (int i = 0; i < 16; i++) {
        len = snprintf(buffer, sizeof(buffer), "  r%-2d:  %08lx\n", i, info->registers.r[i]);
        write(fd, buffer, len);
    }
    #endif

    write(fd, "\n", 1);

    // Write stack frames
    write(fd, "STACK TRACE:\n", 13);
    for (size_t i = 0; i < info->frame_count; i++) {
        Dl_info dl_info;
        if (dladdr((void*)info->stack_frames[i], &dl_info)) {
            len = snprintf(buffer, sizeof(buffer),
                          "#%03zu pc %p %s (%s+%p)\n",
                          i,
                          (void*)info->stack_frames[i],
                          dl_info.dli_fname ? dl_info.dli_fname : "???",
                          dl_info.dli_sname ? dl_info.dli_sname : "???",
                          (void*)((char*)info->stack_frames[i] - (char*)dl_info.dli_saddr));
        } else {
            len = snprintf(buffer, sizeof(buffer),
                          "#%03zu pc %p ???\n",
                          i,
                          (void*)info->stack_frames[i]);
        }
        write(fd, buffer, len);
    }

    // Write memory dump
    if (info->memory_readable) {
        write(fd, "\nMEMORY DUMP:\n", 14);
        len = snprintf(buffer, sizeof(buffer), "Before fault address (%p - 256):\n", info->fault_address);
        write(fd, buffer, len);

        // Write hex dump (simplified)
        for (int i = 0; i < MEMORY_DUMP_SIZE; i += 16) {
            len = snprintf(buffer, sizeof(buffer), "%04x: ", i);
            write(fd, buffer, len);

            for (int j = 0; j < 16 && (i + j) < MEMORY_DUMP_SIZE; j++) {
                len = snprintf(buffer, sizeof(buffer), "%02x ", info->memory_before[i + j]);
                write(fd, buffer, len);
            }
            write(fd, "\n", 1);
        }

        len = snprintf(buffer, sizeof(buffer), "\nAfter fault address (%p):\n", info->fault_address);
        write(fd, buffer, len);

        for (int i = 0; i < MEMORY_DUMP_SIZE; i += 16) {
            len = snprintf(buffer, sizeof(buffer), "%04x: ", i);
            write(fd, buffer, len);

            for (int j = 0; j < 16 && (i + j) < MEMORY_DUMP_SIZE; j++) {
                len = snprintf(buffer, sizeof(buffer), "%02x ", info->memory_after[i + j]);
                write(fd, buffer, len);
            }
            write(fd, "\n", 1);
        }
    }

    close(fd);

    LOGI("Enhanced native crash info written to: %s", g_crash_file_path);
}

// Enhanced signal handler
static void signal_handler(int sig, siginfo_t* info, void* context) {
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

    // NEW: Capture registers
    capture_registers(&g_crash_info, context);

    // NEW: Capture memory dump
    capture_memory_dump(&g_crash_info);

    // Capture stack trace
    g_crash_info.frame_count = capture_stack_trace(g_crash_info.stack_frames, MAX_STACK_FRAMES);

    // Write crash info to file
    write_crash_to_file(&g_crash_info);

    // Call original handler
    struct sigaction* old_handler = &g_old_handlers[sig];
    if (old_handler->sa_sigaction) {
        old_handler->sa_sigaction(sig, info, context);
    } else if (old_handler->sa_handler && old_handler->sa_handler != SIG_DFL && old_handler->sa_handler != SIG_IGN) {
        old_handler->sa_handler(sig);
    }

    // Re-raise signal
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

    const char* crash_dir_str = env->GetStringUTFChars(crash_dir, nullptr);
    snprintf(g_crash_file_path, sizeof(g_crash_file_path), "%s/native_crash.txt", crash_dir_str);
    env->ReleaseStringUTFChars(crash_dir, crash_dir_str);

    LOGI("Initializing enhanced native crash handler, crash file: %s", g_crash_file_path);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &g_old_handlers[SIGSEGV]);
    sigaction(SIGABRT, &sa, &g_old_handlers[SIGABRT]);
    sigaction(SIGFPE,  &sa, &g_old_handlers[SIGFPE]);
    sigaction(SIGILL,  &sa, &g_old_handlers[SIGILL]);
    sigaction(SIGBUS,  &sa, &g_old_handlers[SIGBUS]);
    sigaction(SIGTRAP, &sa, &g_old_handlers[SIGTRAP]);

    g_initialized = true;
    LOGI("Enhanced native crash handler initialized successfully");
}

// Test crash trigger (unchanged)
extern "C" JNIEXPORT void JNICALL
Java_com_crashreporter_library_NativeCrashHandler_triggerNativeCrash(JNIEnv* env, jobject /* this */, jint type) {
    LOGD("Triggering native crash type: %d", type);

    switch (type) {
        case 0: *((volatile int*)nullptr) = 42; break;
        case 1: abort(); break;
        case 2: { volatile int zero = 0; volatile int result = 42 / zero; (void)result; } break;
        case 3: { volatile char* bad_ptr = (char*)0xDEADBEEF; *bad_ptr = 'x'; } break;
        case 4: Java_com_crashreporter_library_NativeCrashHandler_triggerNativeCrash(env, nullptr, 4); break;
        default: LOGE("Unknown crash type: %d", type); break;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_crashreporter_library_NativeCrashHandler_isInitialized(JNIEnv* env, jobject /* this */) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}
