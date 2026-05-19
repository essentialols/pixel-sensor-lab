/*
 * SpectralCapture.java — Capture raw spectral data from VD6282 rear light sensor
 * 
 * Uses the ISensorEventConnection binder interface to receive sensor events
 * directly from sensorservice, bypassing the SensorManager framework.
 *
 * The ISensorServer (android.gui.SensorServer) binder has these transactions:
 *   1 = getSensorList(String opPackageName) -> Sensor[]
 *   2 = createSensorEventConnection(String opPackageName, int mode, String attributionTag, int pid, int uid)
 *       -> ISensorEventConnection
 *   3 = isDataInjectionEnabled() -> bool
 *   4 = createSensorDirectConnection(...)
 *   5 = setOperationParameter(...)
 *
 * ISensorEventConnection has:
 *   - getSensorChannel() -> returns a BitTube (file descriptor pair)
 *   - enableDisable(int handle, bool enabled, ...) -> status
 *   - setEventRate(int handle, int us) -> status
 *   - flush() -> status
 *
 * Build:
 *   javac -source 11 -target 11 -d /tmp/sc_cls SpectralCapture.java
 *   d8 --output /tmp/sc_dex /tmp/sc_cls/*.class
 *   adb push /tmp/sc_dex/classes.dex /data/local/tmp/spectral.dex
 *
 * Run:
 *   adb shell "CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -s 65545 -n 200"
 */
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.FileDescriptor;
import java.io.FileInputStream;

public class SpectralCapture {
    static Class<?> parcelClass;
    static Method obtain, writeToken, writeStr, writeInt, writeFloat;
    static Method readInt, readStr, readFloat, readLong;
    static Method readStrongBinder, readFileDescriptor;
    static Method recycle, transact, setPos, getPos, getSize;
    static Class<?> ibClass;

    static Object newParcel() throws Exception { return obtain.invoke(null); }
    
    static void initReflection() throws Exception {
        parcelClass = Class.forName("android.os.Parcel");
        ibClass = Class.forName("android.os.IBinder");
        obtain = parcelClass.getMethod("obtain");
        writeToken = parcelClass.getMethod("writeInterfaceToken", String.class);
        writeStr = parcelClass.getMethod("writeString", String.class);
        writeInt = parcelClass.getMethod("writeInt", int.class);
        writeFloat = parcelClass.getMethod("writeFloat", float.class);
        readInt = parcelClass.getMethod("readInt");
        readStr = parcelClass.getMethod("readString");
        readFloat = parcelClass.getMethod("readFloat");
        readLong = parcelClass.getMethod("readLong");
        readStrongBinder = parcelClass.getMethod("readStrongBinder");
        readFileDescriptor = parcelClass.getMethod("readRawFileDescriptor");
        recycle = parcelClass.getMethod("recycle");
        transact = ibClass.getMethod("transact", int.class, parcelClass, parcelClass, int.class);
        setPos = parcelClass.getMethod("setDataPosition", int.class);
        getPos = parcelClass.getMethod("dataPosition");
        getSize = parcelClass.getMethod("dataSize");
    }
    
    static boolean listOnly = false;

    // Find sensor handle by type from the sensor list
    static int findSensorHandle(Object sensorServiceBinder, int targetType) throws Exception {
        Object data = newParcel();
        Object reply = newParcel();
        
        writeToken.invoke(data, "android.gui.SensorServer");
        writeStr.invoke(data, "shell");
        transact.invoke(sensorServiceBinder, 1, data, reply, 0);
        
        setPos.invoke(reply, 0);
        int count = (Integer) readInt.invoke(reply);
        
        int foundHandle = -1;
        for (int i = 0; i < count; i++) {
            int flatSize = (Integer) readInt.invoke(reply);
            int dataStart = (Integer) getPos.invoke(reply);
            int dataEnd = dataStart + ((flatSize + 3) & ~3); // align to 4

            try {
                String name = (String) readStr.invoke(reply);
                String vendor = (String) readStr.invoke(reply);
                int version = (Integer) readInt.invoke(reply);
                int handle = (Integer) readInt.invoke(reply);
                int type = (Integer) readInt.invoke(reply);

                if (type == targetType || listOnly) {
                    System.err.println("  type=" + type + " handle=0x" +
                        Integer.toHexString(handle) + " name=\"" + name + "\" vendor=\"" + vendor + "\"");
                    if (type == targetType) foundHandle = handle;
                }
            } catch (Exception e) { /* skip */ }

            setPos.invoke(reply, dataEnd);
        }
        
        recycle.invoke(data);
        recycle.invoke(reply);
        return foundHandle;
    }
    
    public static void main(String[] args) throws Exception {
        int targetType = 65545; // VD6282 rear light
        int maxEvents = 200;
        int rateUs = 16000; // 62.5 Hz
        listOnly = false;

        for (int i = 0; i < args.length; i++) {
            if ("-s".equals(args[i]) && i+1 < args.length) targetType = Integer.parseInt(args[++i]);
            if ("-n".equals(args[i]) && i+1 < args.length) maxEvents = Integer.parseInt(args[++i]);
            if ("-r".equals(args[i]) && i+1 < args.length) rateUs = 1000000 / Integer.parseInt(args[++i]);
            if ("-l".equals(args[i])) listOnly = true;
            if ("-h".equals(args[i])) {
                System.err.println("Usage: SpectralCapture [-l] [-s type] [-n count] [-r hz]");
                System.err.println("  -s TYPE   Sensor type (5=light, 65545=rear_light)");
                System.err.println("  -n COUNT  Events to capture (default 200)");
                System.err.println("  -r HZ     Sample rate (default 62)");
                return;
            }
        }
        
        initReflection();
        
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Object ssBinder = smClass.getMethod("getService", String.class).invoke(null, "sensorservice");
        
        if (ssBinder == null) {
            System.err.println("sensorservice not found");
            System.exit(1);
        }
        
        // List sensors and find target handle
        int handle = findSensorHandle(ssBinder, targetType);
        if (listOnly) { System.exit(0); return; }

        if (handle < 0) {
            // Fallback: known handles from dumpsys sensorservice
            if (targetType == 65545) handle = 0x0101001c; // VD6282 Rear Light
            else if (targetType == 5) handle = 0x01010005; // TMD3719 ALS
            else if (targetType == 131088) handle = 0x01010026; // Auto Brightness
            else {
                System.err.println("Sensor type " + targetType + " not found!");
                System.exit(1);
            }
            System.err.println("Using hardcoded handle 0x" + Integer.toHexString(handle));
        }
        
        // Create sensor event connection (transaction code 2)
        // createSensorEventConnection(String opPackageName, int mode, String attributionTag, int pid, int uid)
        Object data = newParcel();
        Object reply = newParcel();
        
        writeToken.invoke(data, "android.gui.SensorServer");
        writeStr.invoke(data, "shell");         // opPackageName
        writeInt.invoke(data, 0);                // mode (NORMAL=0)
        writeStr.invoke(data, "");               // attributionTag  
        Class<?> procClass = Class.forName("android.os.Process");
        int myPid = (Integer) procClass.getMethod("myPid").invoke(null);
        int myUid = (Integer) procClass.getMethod("myUid").invoke(null);
        writeInt.invoke(data, myPid);   // pid
        writeInt.invoke(data, myUid);   // uid
        
        boolean ok = (Boolean) transact.invoke(ssBinder, 2, data, reply, 0);
        System.err.println("createSensorEventConnection: ok=" + ok + " replySize=" + getSize.invoke(reply));
        
        // Read the ISensorEventConnection binder from reply
        // The reply Parcel contains: ISensorEventConnection binder + BitTube channel
        // No exception code prefix for non-AIDL binder transactions
        setPos.invoke(reply, 0);

        // Try reading binder directly (non-AIDL format)
        Object connBinder = null;
        try {
            connBinder = readStrongBinder.invoke(reply);
        } catch (Exception e) {
            System.err.println("readStrongBinder failed: " + e);
        }
        System.err.println("Connection binder: " + connBinder);

        if (connBinder == null) {
            // Dump raw reply bytes for debugging
            int sz = (Integer) getSize.invoke(reply);
            setPos.invoke(reply, 0);
            System.err.println("Reply size=" + sz + " bytes, dumping ints:");
            for (int off = 0; off + 4 <= sz && off < 64; off += 4) {
                setPos.invoke(reply, off);
                int v = (Integer) readInt.invoke(reply);
                System.err.println("  [" + off + "] = 0x" + Integer.toHexString(v) + " (" + v + ")");
            }
            System.err.println("Got null connection binder");
            recycle.invoke(data);
            recycle.invoke(reply);
            System.exit(1);
        }
        
        // Read the BitTube file descriptor
        // The sensor channel is a BitTube which contains two FDs (read + write)
        // In the binder reply, it's marshalled as a ParcelFileDescriptor
        FileDescriptor sensorFd = null;
        try {
            sensorFd = (FileDescriptor) readFileDescriptor.invoke(reply);
            System.err.println("Sensor channel FD: " + sensorFd);
        } catch (Exception e) {
            System.err.println("Failed to read sensor FD: " + e);
        }
        
        recycle.invoke(data);
        recycle.invoke(reply);
        
        // Enable the sensor via ISensorEventConnection
        // enableDisable(int handle, bool enabled, int64 samplingPeriodNs, int64 maxBatchReportLatencyNs, int reservedFlags)
        String connDesc = "android.gui.ISensorEventConnection";
        
        data = newParcel();
        reply = newParcel();
        writeToken.invoke(data, connDesc);
        writeInt.invoke(data, handle);           // sensor handle
        writeInt.invoke(data, 1);                // enabled = true
        // Write int64 samplingPeriodNs
        Method writeLong = parcelClass.getMethod("writeLong", long.class);
        writeLong.invoke(data, (long)rateUs * 1000L);  // ns
        writeLong.invoke(data, 0L);              // maxBatchReportLatencyNs
        writeInt.invoke(data, 0);                // reservedFlags
        
        // ISensorEventConnection.enableDisable is transaction 1
        ok = (Boolean) transact.invoke(connBinder, 1, data, reply, 0);
        setPos.invoke(reply, 0);
        int enableResult = (Integer) readInt.invoke(reply);
        System.err.println("enableDisable: ok=" + ok + " result=" + enableResult);
        
        recycle.invoke(data);
        recycle.invoke(reply);
        
        if (sensorFd == null) {
            System.err.println("No sensor FD, cannot read events");
            System.exit(1);
        }
        
        // Read sensor events from the BitTube FD
        // Each event is an ASensorEvent struct (104 bytes on 64-bit):
        //   int32_t version (4)
        //   int32_t sensor (4)  
        //   int32_t type (4)
        //   int32_t reserved0 (4)
        //   int64_t timestamp (8)
        //   float[16] data (64)
        //   uint32_t flags (4)
        //   int32_t reserved1[3] (12)
        // Total: 104 bytes
        
        System.out.println("timestamp_ns,type,nvalues,v0,v1,v2,v3,v4,v5,v6,v7,v8,v9,v10,v11,v12,v13,v14,v15");
        System.out.flush();
        
        FileInputStream fis = new FileInputStream(sensorFd);
        byte[] buf = new byte[104];
        int eventCount = 0;
        
        long startTime = System.currentTimeMillis();
        
        while (eventCount < maxEvents && (System.currentTimeMillis() - startTime) < 30000) {
            int n = fis.read(buf);
            if (n < 104) {
                if (n < 0) break;
                // Partial read, try again
                Thread.sleep(1);
                continue;
            }
            
            // Parse ASensorEvent from bytes (little-endian)
            int sensor = getInt32LE(buf, 4);
            int type = getInt32LE(buf, 8);
            long timestamp = getInt64LE(buf, 16);
            
            StringBuilder sb = new StringBuilder();
            sb.append(timestamp).append(",").append(type).append(",16");
            
            for (int j = 0; j < 16; j++) {
                float val = Float.intBitsToFloat(getInt32LE(buf, 24 + j * 4));
                sb.append(",").append(val);
            }
            
            System.out.println(sb.toString());
            eventCount++;
            
            if (eventCount % 50 == 0) {
                System.out.flush();
                System.err.println("  " + eventCount + " events...");
            }
        }
        
        System.out.flush();
        System.err.println("Captured " + eventCount + " events");
        
        // Disable sensor
        data = newParcel();
        reply = newParcel();
        writeToken.invoke(data, connDesc);
        writeInt.invoke(data, handle);
        writeInt.invoke(data, 0); // enabled = false
        writeLong.invoke(data, 0L);
        writeLong.invoke(data, 0L);
        writeInt.invoke(data, 0);
        transact.invoke(connBinder, 1, data, reply, 0);
        recycle.invoke(data);
        recycle.invoke(reply);
        
        fis.close();
        System.exit(0);
    }
    
    static int getInt32LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) |
               ((buf[offset+1] & 0xFF) << 8) |
               ((buf[offset+2] & 0xFF) << 16) |
               ((buf[offset+3] & 0xFF) << 24);
    }
    
    static long getInt64LE(byte[] buf, int offset) {
        return (long)(buf[offset] & 0xFF) |
               ((long)(buf[offset+1] & 0xFF) << 8) |
               ((long)(buf[offset+2] & 0xFF) << 16) |
               ((long)(buf[offset+3] & 0xFF) << 24) |
               ((long)(buf[offset+4] & 0xFF) << 32) |
               ((long)(buf[offset+5] & 0xFF) << 40) |
               ((long)(buf[offset+6] & 0xFF) << 48) |
               ((long)(buf[offset+7] & 0xFF) << 56);
    }
}
