import java.lang.reflect.Method;

public class SensorParse2 {
    static Method readInt, readStr, readFloat, getPos, setPos;
    static Object reply;
    
    static int ri() throws Exception { return (Integer) readInt.invoke(reply); }
    static String rs() throws Exception { return (String) readStr.invoke(reply); }
    static float rf() throws Exception { return (Float) readFloat.invoke(reply); }
    static int pos() throws Exception { return (Integer) getPos.invoke(reply); }
    
    public static void main(String[] args) throws Exception {
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
        
        Object data = obtain.invoke(null);
        reply = obtain.invoke(null);
        
        writeToken.invoke(data, "android.gui.SensorServer");
        writeStr.invoke(data, "shell");
        transact.invoke(binder, 1, data, reply, 0);
        int totalSize = (Integer) getSize.invoke(reply);
        
        setPos.invoke(reply, 0);
        int count = ri();
        System.err.println("Sensor count: " + count + " (reply size: " + totalSize + " bytes)");
        
        for (int i = 0; i < count; i++) {
            int startPos = pos();
            try {
                // Flattenable format: first int is the flattened size
                int flatSize = ri();
                int dataStart = pos();
                int dataEnd = dataStart + flatSize;
                
                String name = rs();
                String vendor = rs();
                int version = ri();
                int handle = ri();
                int type = ri();
                float maxRange = rf();
                float resolution = rf();
                float power = rf();
                int minDelay = ri();
                int fifoReserved = ri();
                int fifoMax = ri();
                String stringType = rs();
                String requiredPerm = rs();
                int maxDelay = ri();
                int flags = ri();
                
                float maxHz = minDelay > 0 ? 1000000.0f / minDelay : 0;
                System.out.println(String.format("type=%d handle=0x%08x name=\"%s\" vendor=\"%s\" maxHz=%.1f flags=0x%x",
                    type, handle, name, vendor, maxHz, flags));
                
                // Jump to end of this entry
                setPos.invoke(reply, dataEnd);
                
            } catch (Exception e) {
                System.err.println("[" + i + "] at pos " + pos() + " (start=" + startPos + "): " + e);
                break;
            }
        }
        
        recycle.invoke(data);
        recycle.invoke(reply);
        System.exit(0);
    }
}
