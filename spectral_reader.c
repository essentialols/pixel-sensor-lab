/*
 * spectral_reader.c — Read raw spectral data from VD6282 rear light sensor
 * and TMD3719 ambient light sensor on Pixel 7 Pro via Android sensor API.
 *
 * Uses dlopen() to load libandroid.so at runtime and call ASensorManager.
 * This lets us compile with musl (static) but still use the Android sensor framework.
 *
 * Build: aarch64-unknown-linux-musl-gcc -static -O2 -o spectral_reader spectral_reader.c -ldl
 * Push:  adb push spectral_reader /data/local/tmp/
 * Run:   adb shell su -c /data/local/tmp/spectral_reader [-n count] [-r rate_hz] [-s sensor_type]
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <signal.h>
#include <time.h>

/* Android sensor event structure (from NDK headers) */
typedef struct {
    int32_t version;
    int32_t sensor;
    int32_t type;
    int32_t reserved0;
    int64_t timestamp;
    union {
        float data[16];
        struct {
            float x, y, z;
        } vector;
        struct {
            float light;      /* lux for TYPE_LIGHT */
        } light;
    };
    uint32_t flags;
    int32_t reserved1[3];
} ASensorEvent;

/* Opaque types */
typedef struct ASensorManager ASensorManager;
typedef struct ASensor ASensor;
typedef struct ASensorEventQueue ASensorEventQueue;
typedef struct ALooper ALooper;

/* Function pointer types */
typedef ASensorManager* (*pfn_GetInstance)(void);
typedef ASensorManager* (*pfn_GetInstanceForPackage)(const char*);
typedef int (*pfn_GetSensorList)(ASensorManager*, const ASensor***);
typedef const ASensor* (*pfn_GetDefaultSensor)(ASensorManager*, int);
typedef ASensorEventQueue* (*pfn_CreateEventQueue)(ASensorManager*, ALooper*, int, void*, void*);
typedef int (*pfn_EnableSensor)(ASensorEventQueue*, const ASensor*);
typedef int (*pfn_DisableSensor)(ASensorEventQueue*, const ASensor*);
typedef int (*pfn_SetEventRate)(ASensorEventQueue*, const ASensor*, int32_t);
typedef int (*pfn_HasEvents)(ASensorEventQueue*);
typedef ssize_t (*pfn_GetEvents)(ASensorEventQueue*, ASensorEvent*, size_t);
typedef int (*pfn_DestroyEventQueue)(ASensorManager*, ASensorEventQueue*);
typedef int (*pfn_GetType)(const ASensor*);
typedef const char* (*pfn_GetName)(const ASensor*);
typedef const char* (*pfn_GetVendor)(const ASensor*);
typedef const char* (*pfn_GetStringType)(const ASensor*);
typedef int (*pfn_GetMinDelay)(const ASensor*);

typedef ALooper* (*pfn_ALooper_prepare)(int);
typedef int (*pfn_ALooper_pollAll)(int, int*, int*, void**);

/* Global function pointers */
static pfn_GetInstance fn_GetInstance;
static pfn_GetInstanceForPackage fn_GetInstanceForPackage;
static pfn_GetSensorList fn_GetSensorList;
static pfn_GetDefaultSensor fn_GetDefaultSensor;
static pfn_CreateEventQueue fn_CreateEventQueue;
static pfn_EnableSensor fn_EnableSensor;
static pfn_DisableSensor fn_DisableSensor;
static pfn_SetEventRate fn_SetEventRate;
static pfn_HasEvents fn_HasEvents;
static pfn_GetEvents fn_GetEvents;
static pfn_DestroyEventQueue fn_DestroyEventQueue;
static pfn_GetType fn_GetType;
static pfn_GetName fn_GetName;
static pfn_GetVendor fn_GetVendor;
static pfn_GetStringType fn_GetStringType;
static pfn_GetMinDelay fn_GetMinDelay;
static pfn_ALooper_prepare fn_Looper_prepare;
static pfn_ALooper_pollAll fn_Looper_pollAll;

static volatile int running = 1;

static void sighandler(int sig) {
    (void)sig;
    running = 0;
}

static int load_android_apis(void)
{
    void *libandroid = dlopen("libandroid.so", RTLD_NOW);
    if (!libandroid) {
        fprintf(stderr, "dlopen(libandroid.so): %s\n", dlerror());
        /* Try full path */
        libandroid = dlopen("/system/lib64/libandroid.so", RTLD_NOW);
        if (!libandroid) {
            fprintf(stderr, "dlopen(/system/lib64/libandroid.so): %s\n", dlerror());
            return -1;
        }
    }

    fn_GetInstance = (pfn_GetInstance)dlsym(libandroid, "ASensorManager_getInstance");
    fn_GetInstanceForPackage = (pfn_GetInstanceForPackage)dlsym(libandroid, "ASensorManager_getInstanceForPackage");
    fn_GetSensorList = (pfn_GetSensorList)dlsym(libandroid, "ASensorManager_getSensorList");
    fn_GetDefaultSensor = (pfn_GetDefaultSensor)dlsym(libandroid, "ASensorManager_getDefaultSensor");
    fn_CreateEventQueue = (pfn_CreateEventQueue)dlsym(libandroid, "ASensorManager_createEventQueue");
    fn_EnableSensor = (pfn_EnableSensor)dlsym(libandroid, "ASensorEventQueue_enableSensor");
    fn_DisableSensor = (pfn_DisableSensor)dlsym(libandroid, "ASensorEventQueue_disableSensor");
    fn_SetEventRate = (pfn_SetEventRate)dlsym(libandroid, "ASensorEventQueue_setEventRate");
    fn_HasEvents = (pfn_HasEvents)dlsym(libandroid, "ASensorEventQueue_hasEvents");
    fn_GetEvents = (pfn_GetEvents)dlsym(libandroid, "ASensorEventQueue_getEvents");
    fn_DestroyEventQueue = (pfn_DestroyEventQueue)dlsym(libandroid, "ASensorManager_destroyEventQueue");
    fn_GetType = (pfn_GetType)dlsym(libandroid, "ASensor_getType");
    fn_GetName = (pfn_GetName)dlsym(libandroid, "ASensor_getName");
    fn_GetVendor = (pfn_GetVendor)dlsym(libandroid, "ASensor_getVendor");
    fn_GetStringType = (pfn_GetStringType)dlsym(libandroid, "ASensor_getStringType");
    fn_GetMinDelay = (pfn_GetMinDelay)dlsym(libandroid, "ASensor_getMinDelay");

    void *libutils = dlopen("libutils.so", RTLD_NOW);
    if (!libutils) libutils = dlopen("/system/lib64/libutils.so", RTLD_NOW);

    /* ALooper is in libandroid itself on newer Android */
    fn_Looper_prepare = (pfn_ALooper_prepare)dlsym(libandroid, "ALooper_prepare");
    fn_Looper_pollAll = (pfn_ALooper_pollAll)dlsym(libandroid, "ALooper_pollAll");

    if (!fn_Looper_prepare && libutils) {
        fn_Looper_prepare = (pfn_ALooper_prepare)dlsym(libutils, "ALooper_prepare");
        fn_Looper_pollAll = (pfn_ALooper_pollAll)dlsym(libutils, "ALooper_pollAll");
    }

    if (!fn_GetInstance || !fn_GetSensorList || !fn_CreateEventQueue ||
        !fn_EnableSensor || !fn_GetEvents || !fn_Looper_prepare || !fn_Looper_pollAll) {
        fprintf(stderr, "Failed to resolve required symbols\n");
        fprintf(stderr, "  GetInstance: %p\n", (void*)fn_GetInstance);
        fprintf(stderr, "  GetSensorList: %p\n", (void*)fn_GetSensorList);
        fprintf(stderr, "  CreateEventQueue: %p\n", (void*)fn_CreateEventQueue);
        fprintf(stderr, "  EnableSensor: %p\n", (void*)fn_EnableSensor);
        fprintf(stderr, "  GetEvents: %p\n", (void*)fn_GetEvents);
        fprintf(stderr, "  Looper_prepare: %p\n", (void*)fn_Looper_prepare);
        fprintf(stderr, "  Looper_pollAll: %p\n", (void*)fn_Looper_pollAll);
        return -1;
    }

    return 0;
}

static const char* sensor_type_name(int type)
{
    switch (type) {
        case 5:     return "LIGHT";
        case 8:     return "PROXIMITY";
        case 65545: return "REAR_LIGHT";
        default:    return "UNKNOWN";
    }
}

int main(int argc, char **argv)
{
    int count = 200;
    int rate_us = 16000;  /* ~62.5 Hz */
    int target_type = -1; /* -1 = all light sensors */
    int list_only = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-n") == 0 && i+1 < argc) count = atoi(argv[++i]);
        else if (strcmp(argv[i], "-r") == 0 && i+1 < argc) rate_us = 1000000 / atoi(argv[++i]);
        else if (strcmp(argv[i], "-s") == 0 && i+1 < argc) target_type = atoi(argv[++i]);
        else if (strcmp(argv[i], "-l") == 0) list_only = 1;
        else if (strcmp(argv[i], "-h") == 0) {
            fprintf(stderr, "Usage: %s [-l] [-n count] [-r rate_hz] [-s sensor_type]\n", argv[0]);
            fprintf(stderr, "  -l           List all sensors and exit\n");
            fprintf(stderr, "  -n count     Number of events (default 200)\n");
            fprintf(stderr, "  -r rate_hz   Sampling rate in Hz (default 62)\n");
            fprintf(stderr, "  -s type      Sensor type (5=light, 8=prox, 65545=rear_light)\n");
            fprintf(stderr, "               Default: register all light-related sensors\n");
            return 0;
        }
    }

    signal(SIGINT, sighandler);
    signal(SIGTERM, sighandler);

    if (load_android_apis() < 0) {
        return 1;
    }

    fprintf(stderr, "Android sensor API loaded successfully\n");

    /* Get sensor manager */
    ASensorManager *mgr = fn_GetInstanceForPackage ?
        fn_GetInstanceForPackage("com.spectral.reader") :
        fn_GetInstance();
    if (!mgr) {
        fprintf(stderr, "Failed to get ASensorManager instance\n");
        return 1;
    }

    /* List all sensors */
    const ASensor **sensor_list = NULL;
    int n_sensors = fn_GetSensorList(mgr, &sensor_list);
    fprintf(stderr, "Found %d sensors:\n", n_sensors);

    for (int i = 0; i < n_sensors; i++) {
        int type = fn_GetType(sensor_list[i]);
        const char *name = fn_GetName ? fn_GetName(sensor_list[i]) : "?";
        const char *vendor = fn_GetVendor ? fn_GetVendor(sensor_list[i]) : "?";
        const char *stype = fn_GetStringType ? fn_GetStringType(sensor_list[i]) : "?";
        int min_delay = fn_GetMinDelay ? fn_GetMinDelay(sensor_list[i]) : 0;
        float max_rate = min_delay > 0 ? 1000000.0f / min_delay : 0;

        fprintf(stderr, "  [%d] type=%d (%s) name='%s' vendor='%s' stype='%s' maxRate=%.1fHz\n",
                i, type, sensor_type_name(type), name, vendor, stype, max_rate);
    }

    if (list_only) return 0;

    /* Create event queue */
    ALooper *looper = fn_Looper_prepare(0);
    if (!looper) {
        fprintf(stderr, "Failed to prepare ALooper\n");
        return 1;
    }

    ASensorEventQueue *queue = fn_CreateEventQueue(mgr, looper, 0, NULL, NULL);
    if (!queue) {
        fprintf(stderr, "Failed to create event queue\n");
        return 1;
    }

    /* Enable target sensors */
    int enabled = 0;
    for (int i = 0; i < n_sensors; i++) {
        int type = fn_GetType(sensor_list[i]);
        if (target_type >= 0) {
            if (type != target_type) continue;
        } else {
            /* Default: enable all light-related sensors */
            if (type != 5 && type != 8 && type != 65545) continue;
        }

        int ret = fn_EnableSensor(queue, sensor_list[i]);
        if (ret < 0) {
            fprintf(stderr, "Failed to enable sensor type %d: %d\n", type, ret);
            continue;
        }

        if (fn_SetEventRate) {
            fn_SetEventRate(queue, sensor_list[i], rate_us);
        }

        const char *name = fn_GetName ? fn_GetName(sensor_list[i]) : "?";
        fprintf(stderr, "Enabled: type=%d '%s' at %d us interval\n", type, name, rate_us);
        enabled++;
    }

    if (enabled == 0) {
        fprintf(stderr, "No sensors enabled!\n");
        fn_DestroyEventQueue(mgr, queue);
        return 1;
    }

    /* Print CSV header */
    printf("timestamp_ns,sensor,type,type_name,d0,d1,d2,d3,d4,d5,d6,d7,d8,d9,d10,d11,d12,d13,d14,d15\n");
    fflush(stdout);

    /* Read events */
    int total = 0;
    while (running && total < count) {
        int events = fn_Looper_pollAll(100, NULL, NULL, NULL);
        (void)events;

        ASensorEvent ev[16];
        ssize_t n;
        while ((n = fn_GetEvents(queue, ev, 16)) > 0) {
            for (ssize_t i = 0; i < n && total < count; i++) {
                printf("%lld,%d,%d,%s",
                       (long long)ev[i].timestamp,
                       ev[i].sensor,
                       ev[i].type,
                       sensor_type_name(ev[i].type));

                /* Print all 16 data values */
                for (int j = 0; j < 16; j++) {
                    printf(",%.6f", ev[i].data[j]);
                }
                printf("\n");
                total++;
            }
            fflush(stdout);
        }
    }

    /* Cleanup */
    for (int i = 0; i < n_sensors; i++) {
        int type = fn_GetType(sensor_list[i]);
        if (target_type >= 0 && type != target_type) continue;
        if (target_type < 0 && type != 5 && type != 8 && type != 65545) continue;
        fn_DisableSensor(queue, sensor_list[i]);
    }
    fn_DestroyEventQueue(mgr, queue);

    fprintf(stderr, "Captured %d events\n", total);
    return 0;
}
