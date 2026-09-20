package ai.kitt.snowboy;

/**
 * SWIG Java proxy for snowboy::SnowboyDetect.
 *
 * Upstream: https://github.com/Kitt-AI/snowboy (Apache-2.0).
 * Binding surface matches the official Android JNI shared library.
 */
public class SnowboyDetect {
    private transient long swigCPtr;
    protected transient boolean swigCMemOwn;

    protected SnowboyDetect(long cPtr, boolean cMemoryOwn) {
        this.swigCMemOwn = cMemoryOwn;
        this.swigCPtr = cPtr;
    }

    protected static long getCPtr(SnowboyDetect obj) {
        return obj == null ? 0L : obj.swigCPtr;
    }

    protected void finalize() {
        delete();
    }

    public synchronized void delete() {
        if (swigCPtr != 0) {
            if (swigCMemOwn) {
                swigCMemOwn = false;
                snowboyJNI.delete_SnowboyDetect(swigCPtr);
            }
            swigCPtr = 0;
        }
    }

    public SnowboyDetect(String resourceFilename, String modelStr) {
        this(snowboyJNI.new_SnowboyDetect(resourceFilename, modelStr), true);
    }

    public boolean Reset() {
        return snowboyJNI.SnowboyDetect_Reset(swigCPtr, this);
    }

    public int RunDetection(String data, boolean isEnd) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_0(swigCPtr, this, data, isEnd);
    }

    public int RunDetection(String data) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_1(swigCPtr, this, data);
    }

    public int RunDetection(float[] data, int arrayLength, boolean isEnd) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_2(
                swigCPtr, this, data, arrayLength, isEnd);
    }

    public int RunDetection(float[] data, int arrayLength) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_3(swigCPtr, this, data, arrayLength);
    }

    public int RunDetection(short[] data, int arrayLength, boolean isEnd) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_4(
                swigCPtr, this, data, arrayLength, isEnd);
    }

    public int RunDetection(short[] data, int arrayLength) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_5(swigCPtr, this, data, arrayLength);
    }

    public int RunDetection(int[] data, int arrayLength, boolean isEnd) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_6(
                swigCPtr, this, data, arrayLength, isEnd);
    }

    public int RunDetection(int[] data, int arrayLength) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_7(swigCPtr, this, data, arrayLength);
    }

    public void SetSensitivity(String sensitivityStr) {
        snowboyJNI.SnowboyDetect_SetSensitivity(swigCPtr, this, sensitivityStr);
    }

    public String GetSensitivity() {
        return snowboyJNI.SnowboyDetect_GetSensitivity(swigCPtr, this);
    }

    public void SetAudioGain(float audioGain) {
        snowboyJNI.SnowboyDetect_SetAudioGain(swigCPtr, this, audioGain);
    }

    public void UpdateModel() {
        snowboyJNI.SnowboyDetect_UpdateModel(swigCPtr, this);
    }

    public int NumHotwords() {
        return snowboyJNI.SnowboyDetect_NumHotwords(swigCPtr, this);
    }

    public void ApplyFrontend(boolean applyFrontend) {
        snowboyJNI.SnowboyDetect_ApplyFrontend(swigCPtr, this, applyFrontend);
    }

    public int SampleRate() {
        return snowboyJNI.SnowboyDetect_SampleRate(swigCPtr, this);
    }

    public int NumChannels() {
        return snowboyJNI.SnowboyDetect_NumChannels(swigCPtr, this);
    }

    public int BitsPerSample() {
        return snowboyJNI.SnowboyDetect_BitsPerSample(swigCPtr, this);
    }
}
