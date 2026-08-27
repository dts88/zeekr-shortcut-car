package com.kooo.evcam;

/**
 * JNI bridge to native vehicle signal decoder library.
 * All protocol details are hidden in the native layer.
 */
public class VhalNative {

    private static final String TAG = "VhalNative";
    
    private static boolean sLibraryLoaded = false;
    
    static {
        try {
            System.loadLibrary("vhal_decoder");
            sLibraryLoaded = true;
            AppLog.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            sLibraryLoaded = false;
            AppLog.e(TAG, "Failed to load native library: " + e.getMessage());
        } catch (SecurityException e) {
            sLibraryLoaded = false;
            AppLog.e(TAG, "Security error loading native library: " + e.getMessage());
        }
    }
    
    /**
     * 检查 native 库是否已成功加载
     */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    // Event types returned by decode()
    public static final int EVT_TURN_SIGNAL = 1;
    public static final int EVT_DOOR_OPEN   = 2;
    public static final int EVT_DOOR_CLOSE  = 3;
    public static final int EVT_SPEED       = 4;
    public static final int EVT_CUSTOM_KEY  = 5;

    // Turn signal directions
    public static final int DIR_NONE  = 0;
    public static final int DIR_LEFT  = 1;
    public static final int DIR_RIGHT = 2;

    // Door positions
    public static final int DOOR_FL = 1;
    public static final int DOOR_FR = 2;
    public static final int DOOR_RL = 3;
    public static final int DOOR_RR = 4;

    /** Get service host address */
    public static native String getGrpcHost();

    /** Get service port number */
    public static native int getGrpcPort();

    /** Get streaming method full name */
    public static native String getStreamMethod();

    /** Get send-all method full name */
    public static native String getSendAllMethod();

    /**
     * Decode a property batch from the vehicle API stream.
     *
     * @param data raw bytes from the stream
     * @return int array: [numEvents, type1, p1, p2, type2, p1, p2, ...]
     *         Each event is 3 ints: [eventType, param1, param2]
     */
    public static native int[] decode(byte[] data);

    /**
     * Configure custom key property IDs and speed threshold.
     */
    public static native void configureCustomKey(int speedPropId, int buttonPropId,
                                                  float speedThreshold);
}
