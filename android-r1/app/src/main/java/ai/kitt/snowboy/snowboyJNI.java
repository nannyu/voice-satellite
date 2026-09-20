package ai.kitt.snowboy;

/**
 * SWIG-generated JNI bindings for SnowboyDetect.
 *
 * Regenerated surface matches libsnowboy-detect-android.so from the official
 * Kitt-AI Snowboy Android demo (Apache-2.0). Do not replace with wrappers from
 * r1-helper or other GPL dumps.
 */
public class snowboyJNI {
    public static final native long new_SnowboyDetect(String resourceFilename, String modelStr);

    public static final native void delete_SnowboyDetect(long cPtr);

    public static final native boolean SnowboyDetect_Reset(long cPtr, SnowboyDetect self);

    public static final native int SnowboyDetect_RunDetection__SWIG_0(
            long cPtr, SnowboyDetect self, String data, boolean isEnd);

    public static final native int SnowboyDetect_RunDetection__SWIG_1(
            long cPtr, SnowboyDetect self, String data);

    public static final native int SnowboyDetect_RunDetection__SWIG_2(
            long cPtr, SnowboyDetect self, float[] data, int arrayLength, boolean isEnd);

    public static final native int SnowboyDetect_RunDetection__SWIG_3(
            long cPtr, SnowboyDetect self, float[] data, int arrayLength);

    public static final native int SnowboyDetect_RunDetection__SWIG_4(
            long cPtr, SnowboyDetect self, short[] data, int arrayLength, boolean isEnd);

    public static final native int SnowboyDetect_RunDetection__SWIG_5(
            long cPtr, SnowboyDetect self, short[] data, int arrayLength);

    public static final native int SnowboyDetect_RunDetection__SWIG_6(
            long cPtr, SnowboyDetect self, int[] data, int arrayLength, boolean isEnd);

    public static final native int SnowboyDetect_RunDetection__SWIG_7(
            long cPtr, SnowboyDetect self, int[] data, int arrayLength);

    public static final native void SnowboyDetect_SetSensitivity(
            long cPtr, SnowboyDetect self, String sensitivityStr);

    public static final native String SnowboyDetect_GetSensitivity(long cPtr, SnowboyDetect self);

    public static final native void SnowboyDetect_SetAudioGain(
            long cPtr, SnowboyDetect self, float audioGain);

    public static final native void SnowboyDetect_UpdateModel(long cPtr, SnowboyDetect self);

    public static final native int SnowboyDetect_NumHotwords(long cPtr, SnowboyDetect self);

    public static final native void SnowboyDetect_ApplyFrontend(
            long cPtr, SnowboyDetect self, boolean applyFrontend);

    public static final native int SnowboyDetect_SampleRate(long cPtr, SnowboyDetect self);

    public static final native int SnowboyDetect_NumChannels(long cPtr, SnowboyDetect self);

    public static final native int SnowboyDetect_BitsPerSample(long cPtr, SnowboyDetect self);
}
