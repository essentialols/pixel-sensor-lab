// spectral_reader.cpp - Read raw VD6282 spectral data via UsfSpectralApi
//
// Links against /vendor/lib64/libusf.so on Pixel 7 Pro.
// The UsfSpectralApi is a host-side API for reading spectral (R,G,B,IR,CLR1,CLR2)
// data directly from the AOC sensor hub, bypassing the Android SensorManager.

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <android/log.h>
#define ALOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "SpectralReader", fmt, ##__VA_ARGS__)

static volatile int running = 1;
static int sample_count = 0;
static int max_samples = 0;
static FILE* csv_out = NULL;

static void sigint_handler(int sig) {
    (void)sig;
    running = 0;
}

// Reconstructed from libusf.so symbols.
// UsfSpectralApiCallbackInterface is a C++ abstract class with virtual methods.
// We reconstruct the vtable layout based on the symbol analysis.

// Forward declarations matching the USF API
namespace usf {

enum UsfLogLevel : int {
    USF_LOG_SILENT = 0,
    USF_LOG_ERROR = 1,
    USF_LOG_WARN = 2,
    USF_LOG_INFO = 3,
    USF_LOG_DEBUG = 4,
    USF_LOG_VERBOSE = 5
};

enum UsfSensorType : int {
    USF_SENSOR_SPECTRAL = 0,
    USF_SENSOR_FLICKER = 1,
    USF_SENSOR_RLS = 2,
};

// Minimal UsfVector<float> - matches the ABI layout
template<typename T, int HeapEnum = 1>
struct UsfVector {
    T* data;
    size_t size;
    size_t capacity;
};

class UsfSpectralApi;

// Abstract callback interface - must match vtable layout exactly
class UsfSpectralApiCallbackInterface {
public:
    virtual ~UsfSpectralApiCallbackInterface() {}
    // slot 2 (offset 0x10): SensorEvent dispatch target
    virtual void OnSensorEventCallback(UsfSensorType type, unsigned long timestamp,
                                       const UsfVector<float, 1>& data,
                                       unsigned long accuracy) = 0;
    // slot 3 (offset 0x18): likely disconnect
    virtual void OnDisconnectCallback() = 0;
    // slot 4 (offset 0x20): Connect dispatch target
    virtual void OnConnectCallback() = 0;
};

} // namespace usf

// Our callback implementation
// vtable layout: SensorEvent, Connect, Disconnect (no virtual dtor)
class SpectralCallback : public usf::UsfSpectralApiCallbackInterface {
public:
    void OnSensorEventCallback(usf::UsfSensorType type, unsigned long timestamp,
                                const usf::UsfVector<float, 1>& data,
                                unsigned long accuracy) override {
        ALOG("OnSensorEventCallback type=%d size=%zu", (int)type, data.size);
        fprintf(stderr, "CALLBACK: OnSensorEventCallback type=%d size=%zu\n",
                (int)type, data.size);

        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        double wall_time = ts.tv_sec + ts.tv_nsec / 1e9;

        if (sample_count == 0) {
            const char* hdr = "wall_time,aoc_timestamp,ch0,ch1,ch2,ch3,ch4,ch5,accuracy";
            printf("%s\n", hdr);
            if (csv_out) fprintf(csv_out, "%s\n", hdr);
        }

        printf("%.6f,%lu", wall_time, timestamp);
        if (csv_out) fprintf(csv_out, "%.6f,%lu", wall_time, timestamp);

        for (size_t i = 0; i < data.size && i < 16; i++) {
            printf(",%.6f", data.data[i]);
            if (csv_out) fprintf(csv_out, ",%.6f", data.data[i]);
        }

        printf(",%lu\n", accuracy);
        if (csv_out) fprintf(csv_out, ",%lu\n", accuracy);

        fflush(stdout);
        if (csv_out) fflush(csv_out);

        sample_count++;
        if (max_samples > 0 && sample_count >= max_samples) {
            running = 0;
        }
    }

    void OnConnectCallback() override {
        ALOG("OnConnectCallback fired!");
        fprintf(stderr, "CALLBACK: OnConnectCallback fired!\n");
    }

    void OnDisconnectCallback() override {
        ALOG("OnDisconnectCallback fired!");
        fprintf(stderr, "CALLBACK: OnDisconnectCallback fired!\n");
        running = 0;
    }
};

// Function pointer types for dlsym
typedef void (*UsfClientMgrInitFn)();
typedef void (*UsfClientMgrDeinitFn)();
typedef void (*UsfSpectralApiCreateFn)(usf::UsfSpectralApi**,
                                        usf::UsfSpectralApiCallbackInterface*,
                                        usf::UsfLogLevel);
typedef void (*UsfSpectralApiDestroyFn)(usf::UsfSpectralApi**);

int main(int argc, char* argv[]) {
    int duration_sec = 10;
    const char* csv_path = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-n") == 0 && i + 1 < argc) {
            max_samples = atoi(argv[++i]);
        } else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) {
            duration_sec = atoi(argv[++i]);
        } else if (strcmp(argv[i], "-o") == 0 && i + 1 < argc) {
            csv_path = argv[++i];
        } else if (strcmp(argv[i], "-h") == 0) {
            fprintf(stderr, "Usage: %s [-n samples] [-t seconds] [-o output.csv]\n", argv[0]);
            fprintf(stderr, "  -n N    Capture N samples then exit\n");
            fprintf(stderr, "  -t N    Capture for N seconds (default: 10)\n");
            fprintf(stderr, "  -o PATH Write CSV to file\n");
            return 0;
        }
    }

    signal(SIGINT, sigint_handler);
    signal(SIGTERM, sigint_handler);

    if (csv_path) {
        csv_out = fopen(csv_path, "w");
        if (!csv_out) {
            fprintf(stderr, "Failed to open %s for writing\n", csv_path);
            return 1;
        }
    }

    // Load libusf.so
    void* usf = dlopen("libusf.so", RTLD_NOW);
    if (!usf) {
        fprintf(stderr, "dlopen libusf.so failed: %s\n", dlerror());
        fprintf(stderr, "Trying /vendor/lib64/libusf.so...\n");
        usf = dlopen("/vendor/lib64/libusf.so", RTLD_NOW);
        if (!usf) {
            fprintf(stderr, "dlopen failed: %s\n", dlerror());
            return 1;
        }
    }

    // Resolve symbols (mangled C++ names)
    auto client_init = (UsfClientMgrInitFn)dlsym(usf,
        "_ZN3usf12UsfClientMgr4InitEv");
    auto client_deinit = (UsfClientMgrDeinitFn)dlsym(usf,
        "_ZN3usf12UsfClientMgr6DeinitEv");
    auto spectral_create = (UsfSpectralApiCreateFn)dlsym(usf,
        "_ZN3usf14UsfSpectralApi6CreateEPPS0_PNS_31UsfSpectralApiCallbackInterfaceENS_11UsfLogLevelE");
    auto spectral_destroy = (UsfSpectralApiDestroyFn)dlsym(usf,
        "_ZN3usf14UsfSpectralApi7DestroyEPPS0_");

    if (!client_init || !client_deinit) {
        fprintf(stderr, "Failed to find UsfClientMgr symbols\n");
        return 1;
    }
    if (!spectral_create) {
        fprintf(stderr, "Failed to find UsfSpectralApi::Create\n");
        return 1;
    }

    fprintf(stderr, "Initializing USF client...\n");
    ALOG("TEST: logging works from main thread");
    client_init();

    SpectralCallback callback;
    usf::UsfSpectralApi* api = NULL;

    ALOG("About to create SpectralApi, callback=%p", (void*)&callback);
    fprintf(stderr, "Creating SpectralApi...\n");
    spectral_create(&api, &callback, usf::USF_LOG_INFO);
    ALOG("Create returned, api=%p", (void*)api);

    if (!api) {
        fprintf(stderr, "UsfSpectralApi::Create returned null\n");
        client_deinit();
        return 1;
    }

    // The UsfSpectralImpl object layout:
    // offset 0x00: vtable ptr (UsfSpectralApi vtable)
    // offset 0x08: vtable ptr (UsfApiCallbackInterface vtable)
    // offset 0x18: UsfApi* (internal API)
    // offset 0x20: callback ptr (our SpectralCallback)
    //
    // The ConnectCallback is on the UsfApiCallbackInterface at offset 0x08.
    // Try invoking it manually to trigger sensor subscription.
    uintptr_t* impl = (uintptr_t*)api;
    ALOG("impl vtable[0]=%p, vtable[1]=%p, callback=%p",
         (void*)impl[0], (void*)impl[1], (void*)impl[4]);

    // Find ConnectCallback via the libusf symbol table
    // The thunk _ZThn8_N3usf15UsfSpectralImpl15ConnectCallbackEv is at a known
    // offset from the UsfSpectralImpl vtable. Instead, call the real ConnectCallback
    // directly using the symbol.
    typedef void (*ConnectCallbackFn)(void* this_ptr);
    ConnectCallbackFn connect_cb = (ConnectCallbackFn)dlsym(usf,
        "_ZN3usf15UsfSpectralImpl15ConnectCallbackEv");
    if (connect_cb) {
        ALOG("Found ConnectCallback at %p, calling with this=%p",
             (void*)connect_cb, (void*)api);
        connect_cb(api);
        ALOG("ConnectCallback returned");
    }

    // Bypass UsfSpectralApi session management: call StartSampling on the
    // internal UsfApiImpl directly.
    //
    // UsfSpectralImpl layout:
    //   offset 0x18: UsfApi* (internal API pointer, set by Init/Create)
    //
    // StartSampling signature:
    //   int StartSampling(UsfSensorType type, const char* name, long long period_ns,
    //                     long long max_latency_ns, UsfSensorReportingMode mode,
    //                     unsigned long& handle, unsigned int flags, unsigned int batch, bool)
    uintptr_t* impl_fields = (uintptr_t*)api;
    void* internal_api = (void*)impl_fields[3]; // offset 0x18 / 8 = index 3
    ALOG("Internal UsfApi ptr at offset 0x18: %p", internal_api);

    if (internal_api) {
        // StartSampling is a virtual method on UsfApiImpl
        // UsfSensorType 12 = spectral sensor
        // UsfSensorReportingMode 2 = ON_CHANGE
        typedef int (*StartSamplingFn)(void* this_ptr, int sensor_type, const char* name,
                                        long long period_ns, long long max_latency_ns,
                                        int reporting_mode, unsigned long* handle,
                                        unsigned int flags, unsigned int batch, bool wakeup);
        auto start_sampling = (StartSamplingFn)dlsym(usf,
            "_ZN3usf10UsfApiImpl13StartSamplingENS_13UsfSensorTypeEPKcllNS_22UsfSensorReportingModeERmjjb");
        if (start_sampling) {
            unsigned long sample_handle = 0;
            ALOG("Calling StartSampling: type=12 (spectral), period=100ms, on-change mode");
            int ret = start_sampling(internal_api, 12, "VD6282 Spectral Sensor",
                                     100000000LL, 0LL, 2, &sample_handle, 0, 0, false);
            ALOG("StartSampling returned %d, handle=%lu", ret, sample_handle);
        } else {
            ALOG("StartSampling not found: %s", dlerror());
        }
    }

    ALOG("Setup complete, waiting for events...");
    sleep(3);

    fprintf(stderr, "Spectral API created. Capturing for %d seconds (Ctrl+C to stop)...\n",
            duration_sec);

    time_t start = time(NULL);
    while (running) {
        usleep(10000); // 10ms poll
        if (duration_sec > 0 && (time(NULL) - start) >= duration_sec) {
            break;
        }
    }

    fprintf(stderr, "\nCaptured %d samples\n", sample_count);

    if (spectral_destroy) {
        spectral_destroy(&api);
    }
    client_deinit();

    if (csv_out) {
        fclose(csv_out);
        fprintf(stderr, "CSV saved to %s\n", csv_path);
    }

    dlclose(usf);
    return 0;
}
