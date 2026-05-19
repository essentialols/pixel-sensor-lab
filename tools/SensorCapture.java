/*
 * SensorCapture.java — Read raw sensor data from Pixel 7 Pro sensors
 * via Android sensor framework using app_process.
 *
 * Uses raw binder IPC to talk to sensorservice. Does NOT use ActivityThread
 * (which causes SIGKILL on rooted LineageOS).
 *
 * Build:
 *   javac -source 11 -target 11 -d /tmp/sc_cls tools/SensorCapture.java
 *   d8 --output /tmp/sc_dex /tmp/sc_cls/SensorCapture.class
 *   adb push /tmp/sc_dex/classes.dex /data/local/tmp/sensor_capture.dex
 *
 * Run:
 *   adb shell "CLASSPATH=/data/local/tmp/sensor_capture.dex app_process / SensorCapture -l"
 *   adb shell "CLASSPATH=/data/local/tmp/sensor_capture.dex app_process / SensorCapture -s 65545 -n 100"
 *
 * Sensor types:
 *   5     = TYPE_LIGHT (TMD3719 Ambient Light)
 *   8     = TYPE_PROXIMITY (TMD3719 Proximity)
 *   65545 = com.google.sensor.rear_light (VD6282 Rear Light - 6 spectral channels!)
 */
import java.lang.reflect.Method;

public class SensorCapture {
    static Method readInt, readStr, readFloat, getPos, setPos;
    static Object reply;
    
    static int ri() throws Exception { return (Integer) readInt.invoke(reply); }
    static String rs() throws Exception { return (String) readStr.invoke(reply); }
    static float rf() throws Exception { return (Float) readFloat.invoke(reply); }
    static int pos() throws Exception { return (Integer) getPos.invoke(reply); }
    
    public static void main(String[] args) throws Exception {
        boolean listOnly = false;
        int targetType = -1;
        int maxEvents = 100;
        
        for (int i = 0; i < args.length; i++) {
            if ("-l".equals(args[i])) listOnly = true;
            if ("-s".equals(args[i]) && i+1 < args.length) targetType = Integer.parseInt(args[++i]);
            if ("-n".equals(args[i]) && i+1 < args.length) maxEvents = Integer.parseInt(args[++i]);
            if ("-h".equals(args[i])) {
                System.err.println("Usage: SensorCapture [-l] [-s type] [-n count]");
                return;
            }
        }
        
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Class<?> ibClass = Class.forName("android.os.IBinder");
        Class<?> parcelClass = Class.forName("android.os.Parcel");
        
        Method obtain = parcelClass.getMethod("obtain");
        Method writeToken = parcelClass.getMethod("writeInterfaceToken", String.class);
        Method writeStr = parcelClass.getMethod("writeString", String.class);
        readInt = parcelClass.getMethod("readInt");
        readStr = parcelClass.getMethod("readString");
        readFloat = parcelClass.getMethod("readFloat");
        Method recycle = parcelClass.getMethod("recycle");
        Method transact = ibClass.getMethod("transact", int.class, parcelClass, parcelClass, int.class);
        setPos = parcelClass.getMethod("setDataPosition", int.class);
        getPos = parcelClass.getMethod("dataPosition");
        Method getSize = parcelClass.getMethod("dataSize");
        
        Object binder = smClass.getMethod("getService", String.class).invoke(null, "sensorservice");
        
        // === LIST SENSORS ===
        Object data = obtain.invoke(null);
        reply = obtain.invoke(null);
        
        writeToken.invoke(data, "android.gui.SensorServer");
        writeStr.invoke(data, "shell");
        transact.invoke(binder, 1, data, reply, 0);
        
        setPos.invoke(reply, 0);
        int count = ri();
        System.err.println("Sensors: " + count);
        
        for (int i = 0; i < count; i++) {
            int flatSize = ri();
            int dataStart = pos();
            int dataEnd = dataStart + flatSize;
            
            try {
                String name = rs();
                String vendor = rs();
                int version = ri();
                int handle = ri();
                int type = ri();
                float maxRange = rf();
                float resolution = rf();
                float power = rf();
                int minDelay = ri();
                
                float maxHz = minDelay > 0 ? 1000000.0f / minDelay : 0;
                System.err.println(String.format("  type=%d handle=0x%08x name=\"%s\" vendor=\"%s\" maxHz=%.1f",
                    type, handle, name, vendor, maxHz));
            } catch (Exception e) {
                System.err.println("  [" + i + "] parse error");
            }
            setPos.invoke(reply, dataEnd);
        }
        
        recycle.invoke(data);
        recycle.invoke(reply);
        
        if (listOnly) { System.exit(0); return; }
        
        // === CAPTURE EVENTS ===
        // TODO: Implement IEventQueueCallback via binder to receive sensor events.
        // The ISensorServer.createEventQueue() transaction requires implementing
        // a binder callback object, which is complex in pure Java reflection.
        //
        // Current workaround: use dumpsys sensorservice polling (see capture_spectral.sh)
        
        System.err.println("\nDirect event capture not yet implemented.");
        System.err.println("Use capture_spectral.sh for polling-based capture.");
        System.exit(0);
    }
}
