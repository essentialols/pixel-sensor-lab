// ripeness_daemon.cpp - Multi-sensor fruit ripeness capture daemon
//
// Combines VD6282 spectral (6 channels) + VL53L1 ToF (940nm reflectance)
// into a single streaming JSON output. Designed to feed an Android APK
// via a local TCP socket or stdout.
//
// Build (NDK):
//   $CC -std=c++17 -O2 -static-libstdc++ --sysroot=$SYSROOT \
//       -o ripeness_daemon ripeness_daemon.cpp -ldl -llog
//
// Usage:
//   ripeness_daemon                     # stdout JSON lines
//   ripeness_daemon -p 8765             # TCP server on port 8765
//   ripeness_daemon -o /data/local/tmp/capture.jsonl  # file output

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <android/log.h>

#define ALOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "RipenessDaemon", fmt, ##__VA_ARGS__)

static volatile int running = 1;
static int sample_count = 0;
static int max_samples = 0;
static unsigned long seq = 0; // monotonic sequence number
static FILE* out_file = NULL;
static int server_fd = -1;
static int client_fd = -1;
static pthread_mutex_t output_mutex = PTHREAD_MUTEX_INITIALIZER;

// Latest spectral reading (written by callback thread, read by output)
static volatile int spectral_ready = 0;
static float spectral_channels[14];
static unsigned long spectral_accuracy = 0;

static void sigint_handler(int sig) {
    (void)sig;
    running = 0;
}

// ============================================================
// VD6282 Spectral Sensor (via UsfSpectralApi)
// ============================================================

namespace usf {
enum UsfLogLevel : int { USF_LOG_INFO = 3 };
enum UsfSensorType : int {};
enum UsfHeapEnum : int {};
template<typename T, int H = 1> struct UsfVector { T* data; size_t size; size_t capacity; };
class UsfSpectralApi;
class UsfSpectralApiCallbackInterface {
public:
    virtual ~UsfSpectralApiCallbackInterface() {}
    virtual void OnSensorEventCallback(UsfSensorType type, unsigned long session_idx,
                                       const UsfVector<float, 1>& data_ref,
                                       unsigned long accuracy) = 0;
    virtual void OnDisconnectCallback() = 0;
    virtual void OnConnectCallback() = 0;
};
}

class SpectralCallback : public usf::UsfSpectralApiCallbackInterface {
public:
    void OnSensorEventCallback(usf::UsfSensorType, unsigned long,
                                const usf::UsfVector<float, 1>& data_ref,
                                unsigned long accuracy) override {
        const float* fdata = (const float*)((const char*)&data_ref + 32);
        pthread_mutex_lock(&output_mutex);
        for (int i = 0; i < 14; i++) spectral_channels[i] = fdata[i];
        spectral_accuracy = accuracy;
        spectral_ready = 1;
        pthread_mutex_unlock(&output_mutex);
    }
    void OnConnectCallback() override { ALOG("Spectral: connected"); }
    void OnDisconnectCallback() override { ALOG("Spectral: disconnected"); running = 0; }
};

typedef void (*UsfClientMgrInitFn)();
typedef void (*UsfClientMgrDeinitFn)();
typedef void (*UsfSpectralApiCreateFn)(usf::UsfSpectralApi**, usf::UsfSpectralApiCallbackInterface*, usf::UsfLogLevel);
typedef void (*UsfSpectralApiDestroyFn)(usf::UsfSpectralApi**);
typedef void (*ConnectCallbackFn)(void*);
typedef int (*StartSamplingFn)(void*, int, const char*, long long, long long, int, unsigned long*, unsigned int, unsigned int, bool);

static usf::UsfSpectralApi* spectral_api = NULL;
static void* usf_lib = NULL;

static int init_spectral() {
    usf_lib = dlopen("libusf.so", RTLD_NOW);
    if (!usf_lib) usf_lib = dlopen("/vendor/lib64/libusf.so", RTLD_NOW);
    if (!usf_lib) { ALOG("Failed to load libusf.so"); return -1; }

    auto client_init = (UsfClientMgrInitFn)dlsym(usf_lib, "_ZN3usf12UsfClientMgr4InitEv");
    auto spectral_create = (UsfSpectralApiCreateFn)dlsym(usf_lib,
        "_ZN3usf14UsfSpectralApi6CreateEPPS0_PNS_31UsfSpectralApiCallbackInterfaceENS_11UsfLogLevelE");
    if (!client_init || !spectral_create) { ALOG("Missing USF symbols"); return -1; }

    client_init();

    static SpectralCallback callback;
    spectral_create(&spectral_api, &callback, usf::USF_LOG_INFO);
    if (!spectral_api) { ALOG("SpectralApi::Create failed"); return -1; }

    // Manually trigger ConnectCallback
    auto connect_cb = (ConnectCallbackFn)dlsym(usf_lib,
        "_ZN3usf15UsfSpectralImpl15ConnectCallbackEv");
    if (connect_cb) connect_cb(spectral_api);

    // Start sampling on the internal UsfApiImpl
    uintptr_t* impl = (uintptr_t*)spectral_api;
    void* internal_api = (void*)impl[3]; // offset 0x18
    if (!internal_api) { ALOG("No internal API"); return -1; }

    auto start_sampling = (StartSamplingFn)dlsym(usf_lib,
        "_ZN3usf10UsfApiImpl13StartSamplingENS_13UsfSensorTypeEPKcllNS_22UsfSensorReportingModeERmjjb");
    if (!start_sampling) { ALOG("StartSampling not found"); return -1; }

    unsigned long handle = 0;
    int ret = start_sampling(internal_api, 12, "VD6282 Spectral Sensor",
                             100000000LL, 0LL, 1, &handle, 0, 0, false);
    if (ret != 0) { ALOG("StartSampling failed: %d", ret); return -1; }

    // Inject session cookie
    volatile uintptr_t* cookie = (uintptr_t*)((char*)spectral_api + 0x58);
    volatile uintptr_t* active = (uintptr_t*)((char*)spectral_api + 0x28);
    *cookie = handle;
    *active = 1;

    ALOG("Spectral sensor initialized, handle=0x%lx", handle);
    return 0;
}

// ============================================================
// VL53L1 ToF Sensor (940nm reflectance via ioctl + I2C)
// ============================================================

#include <sys/ioctl.h>
#include <linux/i2c.h>
#include <linux/i2c-dev.h>

#define RANGING_DEV  "/dev/ispolin_ranging"
#define INPUT_DEV    "/dev/input/event3"
#define LWIS_DEV     "/dev/lwis-sensor-nagual"
#define I2C_DEV      "/dev/i2c-1"
#define VL53L1_ADDR  0x29
#define NUM_BINS     24
#define HIST_BASE    0x008E
#define VL53L1_IOCTL_START    _IO('p', 0x01)
#define VL53L1_IOCTL_STOP     _IO('p', 0x05)
#define VL53L1_IOCTL_POWER_UP _IO('p', 0x06)
#define VL53L1_IOCTL_PARAM    0xC014700D
#define LWIS_IOCTL_POWER_ON   0xC0104C64

static int range_fd = -1, lwis_fd = -1, i2c_fd = -1, input_fd = -1;
static int tof_available = 0;

static int i2c_read_reg16(uint16_t reg, uint8_t *buf, int len) {
    uint8_t rb[2] = { (uint8_t)(reg >> 8), (uint8_t)(reg & 0xFF) };
    struct i2c_msg msgs[2] = {
        { .addr = VL53L1_ADDR, .flags = 0, .len = 2, .buf = rb },
        { .addr = VL53L1_ADDR, .flags = I2C_M_RD, .len = (uint16_t)len, .buf = buf }
    };
    struct i2c_rdwr_ioctl_data d = { .msgs = msgs, .nmsgs = 2 };
    return ioctl(i2c_fd, I2C_RDWR, &d) < 0 ? -1 : 0;
}

static int read_tof_histogram(uint32_t bins[NUM_BINS], int *distance_mm) {
    // Try to read distance from input event
    *distance_mm = -1;
    if (input_fd >= 0) {
        struct input_event ev;
        while (read(input_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_ABS && ev.code == 0x13) {
                *distance_mm = ev.value & 0xFFFF;
            }
        }
    }

    // Read histogram via I2C (24 bins * 3 bytes per bin)
    if (i2c_fd < 0) return -1;
    uint8_t raw[NUM_BINS * 3];
    if (i2c_read_reg16(HIST_BASE, raw, sizeof(raw)) < 0) return -1;
    uint32_t total = 0;
    for (int i = 0; i < NUM_BINS; i++) {
        bins[i] = ((uint32_t)raw[i*3] << 16) | ((uint32_t)raw[i*3+1] << 8) | raw[i*3+2];
        total += bins[i];
    }
    return total > 0 ? 0 : -1;
}

static int lwis_power_on() {
    struct { uint32_t cmd_id; uint32_t ret; uint64_t pad[8]; } pkt = {};
    pkt.cmd_id = 0x00010100;
    lwis_fd = open(LWIS_DEV, O_RDWR);
    if (lwis_fd < 0) return -1;
    if (ioctl(lwis_fd, LWIS_IOCTL_POWER_ON, &pkt) < 0) {
        close(lwis_fd);
        lwis_fd = -1;
        return -1;
    }
    return 0;
}

static int set_tof_param(int id, int val) {
    struct { uint32_t is_read; uint32_t name; int32_t val; int32_t v2; int32_t st; } p = {};
    p.name = id;
    p.val = val;
    return ioctl(range_fd, VL53L1_IOCTL_PARAM, &p);
}

static int init_tof() {
    // Power on via LWIS
    if (lwis_power_on() < 0) {
        ALOG("ToF: lwis power failed (need root + setenforce 0)");
        return -1;
    }
    usleep(300000);

    // Open and configure ranging device
    range_fd = open(RANGING_DEV, O_RDWR);
    if (range_fd < 0) {
        ALOG("ToF: ranging dev open failed");
        close(lwis_fd);
        return -1;
    }

    // Power up and stop before configuring
    if (ioctl(range_fd, VL53L1_IOCTL_POWER_UP, NULL) < 0) {
        ALOG("ToF: POWER_UP ioctl failed");
        close(range_fd);
        close(lwis_fd);
        return -1;
    }
    usleep(50000);

    ioctl(range_fd, VL53L1_IOCTL_STOP, NULL); // may fail if not running, that's OK

    // Set timing budget (2000us = fast mode, ~33Hz)
    set_tof_param(11, 2000);

    // Start ranging
    if (ioctl(range_fd, VL53L1_IOCTL_START, NULL) < 0) {
        ALOG("ToF: START ioctl failed");
        close(range_fd);
        close(lwis_fd);
        return -1;
    }

    // Open input event device for distance readings
    input_fd = open(INPUT_DEV, O_RDONLY | O_NONBLOCK);
    if (input_fd < 0) {
        ALOG("ToF: input event open failed (distance unavailable)");
    }

    // Open I2C for histogram reading
    i2c_fd = open(I2C_DEV, O_RDWR);
    if (i2c_fd < 0) {
        ALOG("ToF: I2C open failed (histogram unavailable)");
    }

    tof_available = 1;
    ALOG("ToF sensor initialized");
    return 0;
}

// ============================================================
// Output: JSON lines with spectral + ToF + ripeness indices
// ============================================================

static void emit_json(FILE* out) {
    float r, g, b, ir, c1, c2, gain;
    unsigned long acc;

    pthread_mutex_lock(&output_mutex);
    if (!spectral_ready) { pthread_mutex_unlock(&output_mutex); return; }
    r = spectral_channels[2]; g = spectral_channels[3];
    b = spectral_channels[4]; ir = spectral_channels[5];
    c1 = spectral_channels[6]; c2 = spectral_channels[7];
    gain = spectral_channels[8];
    acc = spectral_accuracy;
    spectral_ready = 0;
    pthread_mutex_unlock(&output_mutex);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    double wall = ts.tv_sec + ts.tv_nsec / 1e9;

    float vis = r + g + b;
    float ndvi = (ir + r) > 0 ? (ir - r) / (ir + r) : 0;
    float rg = g > 0 ? r / g : 0;
    float bg = g > 0 ? b / g : 0;
    float nir_vis = vis > 0 ? ir / vis : 0;
    float clr = c2 > 0 ? c1 / c2 : 0;
    float cidx = g > 0 ? (r - b) / g : 0;

    // ToF data
    uint32_t tof_bins[NUM_BINS] = {};
    int tof_distance = -1;
    uint32_t tof_photons = 0;
    if (tof_available && i2c_fd >= 0) {
        if (read_tof_histogram(tof_bins, &tof_distance) == 0) {
            for (int i = 0; i < NUM_BINS; i++) tof_photons += tof_bins[i];
        }
    }

    // Lux estimate from green channel (factory calibration: g_to_lux = 109.58)
    float lux_est = gain > 0 ? (g / gain) / 109.58f : 0;

    // Spectral fractions (normalized to visible total)
    float r_frac = vis > 0 ? r / vis : 0;
    float g_frac = vis > 0 ? g / vis : 0;
    float b_frac = vis > 0 ? b / vis : 0;

    seq++;

    fprintf(out, "{\"seq\":%lu,\"t\":%.3f,\"aoc_ts\":%lu,\"gain\":%.0f,\"lux\":%.1f,"
            "\"raw\":{\"R\":%.0f,\"G\":%.0f,\"B\":%.0f,\"IR\":%.0f,\"CLR1\":%.0f,\"CLR2\":%.0f},"
            "\"frac\":{\"R\":%.4f,\"G\":%.4f,\"B\":%.4f},"
            "\"idx\":{\"NDVI\":%.4f,\"RG\":%.4f,\"BG\":%.4f,\"NIR_VIS\":%.4f,\"CLR\":%.4f,\"CI\":%.4f}",
            seq, wall, acc, gain, lux_est,
            r, g, b, ir, c1, c2,
            r_frac, g_frac, b_frac,
            ndvi, rg, bg, nir_vis, clr, cidx);

    if (tof_available && tof_photons > 0 && tof_photons < 100000000u) {
        // Compute ToF histogram stats
        uint32_t peak_val = 0;
        int peak_bin = 0;
        double centroid = 0;
        uint32_t total_valid = tof_photons;
        for (int i = 0; i < NUM_BINS; i++) {
            if (tof_bins[i] > peak_val) { peak_val = tof_bins[i]; peak_bin = i; }
            centroid += (double)i * tof_bins[i];
        }
        if (total_valid > 0) centroid /= total_valid;

        fprintf(out, ",\"tof\":{\"photons\":%u,\"dist_mm\":%d,"
                "\"peak_bin\":%d,\"centroid\":%.2f,\"bins\":[",
                tof_photons, tof_distance, peak_bin, centroid);
        for (int i = 0; i < NUM_BINS; i++) {
            if (i > 0) fprintf(out, ",");
            fprintf(out, "%u", tof_bins[i]);
        }
        fprintf(out, "]}");
    }

    fprintf(out, "}\n");
    fflush(out);
}

// ============================================================
// TCP Server (optional, for APK connection)
// ============================================================

static int start_tcp_server(int port) {
    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) return -1;
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);
    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) return -1;
    if (listen(server_fd, 1) < 0) return -1;
    ALOG("TCP server on port %d", port);
    return 0;
}

// ============================================================
// Main
// ============================================================

int main(int argc, char* argv[]) {
    int duration_sec = 0;
    const char* out_path = NULL;
    int port = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-n") == 0 && i + 1 < argc) max_samples = atoi(argv[++i]);
        else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) duration_sec = atoi(argv[++i]);
        else if (strcmp(argv[i], "-o") == 0 && i + 1 < argc) out_path = argv[++i];
        else if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) port = atoi(argv[++i]);
        else if (strcmp(argv[i], "-h") == 0) {
            fprintf(stderr, "Usage: %s [-n samples] [-t seconds] [-o file.jsonl] [-p port]\n", argv[0]);
            return 0;
        }
    }

    signal(SIGINT, sigint_handler);
    signal(SIGTERM, sigint_handler);

    FILE* output = stdout;
    if (out_path) {
        output = fopen(out_path, "w");
        if (!output) { perror("fopen"); return 1; }
        out_file = output;
    }

    if (port > 0) {
        if (start_tcp_server(port) < 0) { perror("tcp server"); return 1; }
    }

    // Initialize sensors
    ALOG("Initializing sensors...");
    int spectral_ok = init_spectral();
    int tof_ok = init_tof();

    if (spectral_ok < 0 && tof_ok < 0) {
        fprintf(stderr, "No sensors available\n");
        return 1;
    }

    ALOG("Sensors ready: spectral=%s tof=%s",
         spectral_ok == 0 ? "YES" : "NO", tof_ok == 0 ? "YES" : "NO");

    // If TCP server, wait for client
    if (port > 0 && server_fd >= 0) {
        ALOG("Waiting for client connection on port %d...", port);
        client_fd = accept(server_fd, NULL, NULL);
        if (client_fd >= 0) {
            output = fdopen(client_fd, "w");
            ALOG("Client connected");
        }
    }

    time_t start = time(NULL);
    while (running) {
        emit_json(output);
        usleep(50000); // 50ms poll (20Hz max output rate)

        sample_count++;
        if (max_samples > 0 && sample_count >= max_samples) break;
        if (duration_sec > 0 && (time(NULL) - start) >= duration_sec) break;
    }

    ALOG("Captured %d samples", sample_count);

    // Cleanup
    if (tof_available && range_fd >= 0) {
        ioctl(range_fd, VL53L1_IOCTL_STOP, NULL);
        close(range_fd);
    }
    if (lwis_fd >= 0) close(lwis_fd);
    if (i2c_fd >= 0) close(i2c_fd);
    if (input_fd >= 0) close(input_fd);
    if (out_file) fclose(out_file);
    if (client_fd >= 0) close(client_fd);
    if (server_fd >= 0) close(server_fd);

    return 0;
}
