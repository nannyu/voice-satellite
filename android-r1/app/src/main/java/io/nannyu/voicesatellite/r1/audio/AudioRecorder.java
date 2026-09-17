package io.nannyu.voicesatellite.r1.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.util.Arrays;

/** MIT-derived R1 capture/VAD path from kitakeyos-dev/r1-manager. */
public final class AudioRecorder implements AutoCloseable {
    public interface Listener {
        void onSpeechStart();
        void onAudioFrame(byte[] pcm30ms);
        void onSpeechEnd();
        void onError(String message);
    }
    private static final int RATE = 16000, FRAME_BYTES = 960, PRE_ROLL = 10;
    private static final int END_SILENCE_FRAMES = 27, MIN_SPEECH_FRAMES = 3;
    private volatile Listener listener;
    private volatile boolean amplitudeMode;
    private final CaptureWorker worker = new CaptureWorker(FRAME_BYTES, 1000, this::error);

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean start() { return worker.start(CaptureSession::new); }
    public boolean isRunning() { return worker.isRunning(); }
    public String getVadMode() { return amplitudeMode ? "amplitude" : "libfvad"; }
    @Override public void close() { worker.close(); }
    private void error(String message) { Listener target = listener; if (target != null) target.onError(message); }

    private final class CaptureSession implements CaptureWorker.Session {
        private AudioRecord record;
        private VadDetector vad;
        private final byte[][] preRoll = new byte[PRE_ROLL][FRAME_BYTES];
        private int preIndex, preCount, speechFrames, silenceFrames, maxRms;
        private boolean speaking, prioritySet;
        private Listener target;

        @SuppressLint("MissingPermission") @Override public void start() {
            target = listener; // A generation keeps its own callbacks even when the next listener changes.
            amplitudeMode = false;
            try {
                vad = new VadDetector();
                amplitudeMode = !vad.initialize(VadDetector.MODE_AGGRESSIVE);
            } catch (LinkageError unavailable) { amplitudeMode = true; }
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("unsupported AudioRecord configuration: " + min);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, FRAME_BYTES * 4));
            if (record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord initialization failed");
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not start");
        }
        @Override public int read(byte[] frame, int offset, int length) {
            if (!prioritySet) { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); prioritySet = true; }
            return record.read(frame, offset, length);
        }
        @Override public void process(byte[] frame) {
            int rms = rms(frame);
            maxRms = Math.max(maxRms, rms);
            boolean nativeSpeech = false;
            if (!amplitudeMode) {
                try { nativeSpeech = vad.isSpeech(frame, 0, FRAME_BYTES); }
                catch (RuntimeException failure) { amplitudeMode = true; }
            }
            boolean ampSpeech = rms > 20;
            if (!amplitudeMode && ampSpeech && !nativeSpeech && maxRms < 100) amplitudeMode = true;
            boolean speech = amplitudeMode ? ampSpeech : nativeSpeech;
            if (!speaking) {
                System.arraycopy(frame, 0, preRoll[preIndex], 0, FRAME_BYTES);
                preIndex = (preIndex + 1) % PRE_ROLL;
                preCount = Math.min(PRE_ROLL, preCount + 1);
                if (speech && ++speechFrames >= MIN_SPEECH_FRAMES) {
                    speaking = true;
                    if (target != null) {
                        target.onSpeechStart();
                        for (int i = 0; i < preCount; i++)
                            emit(preRoll[(preIndex - preCount + i + PRE_ROLL) % PRE_ROLL]);
                        // The triggering frame is already in pre-roll; do not emit it twice.
                    }
                } else if (!speech) speechFrames = 0;
            } else {
                emit(frame);
                silenceFrames = speech ? 0 : silenceFrames + 1;
                if (silenceFrames >= END_SILENCE_FRAMES) {
                    if (target != null) target.onSpeechEnd();
                    speaking = false;
                    preIndex = preCount = speechFrames = silenceFrames = 0;
                    if (vad != null) vad.reset();
                }
            }
        }
        private void emit(byte[] frame) {
            if (target != null) target.onAudioFrame(Arrays.copyOf(frame, frame.length));
        }
        @Override public void stop() {
            if (record != null && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                record.stop();
        }
        @Override public void close() {
            // Called only after capture/processing has stopped, including initialization failure.
            try { if (record != null) record.release(); }
            finally { if (vad != null) vad.close(); }
        }
    }
    private static int rms(byte[] pcm) {
        long sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return pcm.length == 0 ? 0 : (int) Math.sqrt(sum / (pcm.length / 2));
    }
}
