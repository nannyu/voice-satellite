package io.nannyu.voicesatellite.r1.kws;

import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Independent sherpa-onnx KWS micro-benchmark for R1.
 * Not wired into VoiceSatelliteService / session / transport.
 *
 * Matrix (fixed chunk-8 / ~160 ms models):
 *   INT8 × 1 thread, INT8 × 2 threads, FP32 × 1 thread, FP32 × 2 threads
 *
 * Workload: offline WAV files under {@code /sdcard/kws-probe/} (adb push).
 * Primary verdict metric: RTF — if RTF ≥ 1, the device will fall behind live audio
 * ("still processing the previous utterance").
 */
public final class KwsPerfProbe {
    public interface Listener {
        void onProgress(String text);
        void onComplete(String report);
    }

    private static final String TAG = "KwsPerfProbe";
    private static final String MODEL_DIR_NAME = "kws-probe";
    private static final int SAMPLE_RATE = 16000;
    /** 100 ms feed chunks — matches typical always-on wake cadence. */
    private static final int CHUNK_SAMPLES = 1600;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    public void start(Listener listener) {
        cancelled = false;
        new Thread(() -> run(listener), "r1-kws-perf-probe").start();
    }

    private void run(Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        StringBuilder report = new StringBuilder();
        report.append("R1 KWS PERF PROBE v1\n");
        report.append("engine=sherpa-onnx KeywordSpotter\n");
        report.append("model=zipformer-zh-en-3M chunk-8 (~160ms)\n");
        report.append("workload=offline WAV (no mic, no session)\n\n");

        File root = resolveModelRoot();
        if (root == null) {
            String msg = "FAIL: model dir not found. adb push tools/kws-probe/models/. /sdcard/kws-probe/";
            listener.onProgress(msg);
            listener.onComplete(msg);
            return;
        }
        report.append("model_root=").append(root.getAbsolutePath()).append('\n');

        File keywords = new File(root, "keywords.txt");
        File tokens = new File(root, "tokens.txt");
        File decoder = new File(root, "decoder-epoch-13-avg-2-chunk-8-left-64.onnx");
        File wavDir = new File(root, "wavs");
        if (!keywords.isFile() || !tokens.isFile() || !decoder.isFile() || !wavDir.isDirectory()) {
            String msg = "FAIL: incomplete model root (need tokens/keywords/decoder/wavs)";
            report.append(msg).append('\n');
            listener.onComplete(report.toString());
            return;
        }

        List<File> wavs = listWavs(wavDir);
        if (wavs.isEmpty()) {
            String msg = "FAIL: no .wav under " + wavDir;
            report.append(msg).append('\n');
            listener.onComplete(report.toString());
            return;
        }
        report.append("wav_count=").append(wavs.size()).append('\n');
        for (File w : wavs) report.append("  wav=").append(w.getName()).append('\n');
        report.append('\n');

        Config[] configs = new Config[] {
                new Config("INT8", 1, true),
                new Config("INT8", 2, true),
                new Config("FP32", 1, false),
                new Config("FP32", 2, false),
        };

        List<Row> rows = new ArrayList<>();
        for (Config cfg : configs) {
            if (cancelled) {
                report.append("CANCELLED\n");
                break;
            }
            listener.onProgress("Running " + cfg.label() + " ...");
            Row row = measure(root, cfg, keywords, tokens, decoder, wavs, listener);
            rows.add(row);
            report.append(row.formatBlock()).append('\n');
            // Let the allocator settle between matrices so RSS deltas stay readable.
            System.gc();
            try { Thread.sleep(400); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
            }
        }

        report.append(verdict(rows));
        String text = report.toString();
        writeReportFile(root, text);
        listener.onComplete(text);
    }

    private Row measure(
            File root, Config cfg, File keywords, File tokens, File decoder,
            List<File> wavs, Listener listener) {
        Row row = new Row(cfg);
        File encoder = new File(root, cfg.int8
                ? "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
                : "encoder-epoch-13-avg-2-chunk-8-left-64.onnx");
        File joiner = new File(root, cfg.int8
                ? "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
                : "joiner-epoch-13-avg-2-chunk-8-left-64.onnx");
        if (!encoder.isFile() || !joiner.isFile()) {
            row.error = "missing encoder/joiner for " + cfg.precision;
            return row;
        }

        long rssBefore = nativeHeapKb();
        KeywordSpotter spotter = null;
        try {
            long t0 = SystemClock.elapsedRealtime();
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(encoder.getAbsolutePath());
            transducer.setDecoder(decoder.getAbsolutePath());
            transducer.setJoiner(joiner.getAbsolutePath());

            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(tokens.getAbsolutePath());
            model.setNumThreads(cfg.threads);
            model.setProvider("cpu");
            model.setModelType("zipformer2");
            model.setDebug(false);

            FeatureConfig feat = new FeatureConfig();
            feat.setSampleRate(SAMPLE_RATE);
            feat.setFeatureDim(80);

            KeywordSpotterConfig kcfg = new KeywordSpotterConfig();
            kcfg.setFeatConfig(feat);
            kcfg.setModelConfig(model);
            kcfg.setKeywordsFile(keywords.getAbsolutePath());
            kcfg.setMaxActivePaths(4);
            kcfg.setKeywordsScore(1.0f);
            kcfg.setKeywordsThreshold(0.25f);
            kcfg.setNumTrailingBlanks(1);

            // File paths — AssetManager null uses newFromFile.
            spotter = new KeywordSpotter(null, kcfg);
            row.initMs = SystemClock.elapsedRealtime() - t0;
            row.rssAfterInitKb = nativeHeapKb();

            long decodeWallMs = 0;
            double audioSec = 0;
            int hits = 0;
            int decodeCalls = 0;

            for (File wav : wavs) {
                if (cancelled) break;
                listener.onProgress(cfg.label() + " · " + wav.getName());
                float[] samples = readWavPcm16Mono(wav);
                audioSec += samples.length / (double) SAMPLE_RATE;

                OnlineStream stream = spotter.createStream("");
                try {
                    int offset = 0;
                    while (offset < samples.length) {
                        if (cancelled) break;
                        int n = Math.min(CHUNK_SAMPLES, samples.length - offset);
                        float[] chunk = new float[n];
                        System.arraycopy(samples, offset, chunk, 0, n);
                        offset += n;
                        stream.acceptWaveform(chunk, SAMPLE_RATE);

                        long d0 = SystemClock.elapsedRealtime();
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream);
                            decodeCalls++;
                            KeywordSpotterResult result = spotter.getResult(stream);
                            if (result != null) {
                                String kw = result.getKeyword();
                                if (kw != null && kw.length() > 0) {
                                    hits++;
                                    spotter.reset(stream);
                                }
                            }
                        }
                        decodeWallMs += SystemClock.elapsedRealtime() - d0;
                    }
                    stream.inputFinished();
                    long d1 = SystemClock.elapsedRealtime();
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream);
                        decodeCalls++;
                        KeywordSpotterResult result = spotter.getResult(stream);
                        if (result != null) {
                            String kw = result.getKeyword();
                            if (kw != null && kw.length() > 0) {
                                hits++;
                                spotter.reset(stream);
                            }
                        }
                    }
                    decodeWallMs += SystemClock.elapsedRealtime() - d1;
                } finally {
                    stream.release();
                }
            }

            row.audioSec = audioSec;
            row.decodeWallMs = decodeWallMs;
            row.decodeCalls = decodeCalls;
            row.hits = hits;
            row.rtf = audioSec > 0 ? (decodeWallMs / 1000.0) / audioSec : -1;
            row.rssPeakKb = nativeHeapKb();
            row.rssDeltaKb = row.rssPeakKb - rssBefore;
            row.ok = true;
        } catch (Throwable t) {
            Log.e(TAG, cfg.label() + " failed", t);
            row.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (spotter != null) {
                try { spotter.release(); } catch (Throwable ignored) {}
            }
        }
        return row;
    }

    private static String verdict(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== VERDICT ===\n");
        sb.append("Go if: all configs ok, RTF < 0.50 (prefer < 0.35), init_ms < 8000, no OOM.\n");
        sb.append("No-go if: any RTF >= 1.0 (will backlog live audio) or init/crash failure.\n\n");

        boolean anyFail = false;
        boolean anyBacklog = false;
        boolean allHeadroom = true;
        Row best = null;
        for (Row r : rows) {
            if (!r.ok) { anyFail = true; continue; }
            if (r.rtf >= 1.0) anyBacklog = true;
            if (r.rtf >= 0.50) allHeadroom = false;
            if (best == null || r.rtf < best.rtf) best = r;
        }

        if (anyFail) {
            sb.append("RESULT=NO_GO (at least one config failed to run)\n");
        } else if (anyBacklog) {
            sb.append("RESULT=NO_GO (RTF>=1.0 — device falls behind live audio)\n");
        } else if (!allHeadroom) {
            sb.append("RESULT=MARGINAL (0.50<=RTF<1.0 — not enough headroom for always-on wake)\n");
        } else {
            sb.append("RESULT=GO_CANDIDATE (RTF<0.50 on measured matrix)\n");
        }
        if (best != null && best.ok) {
            sb.append(String.format(Locale.US,
                    "best=%s rtf=%.3f init_ms=%d hits=%d\n",
                    best.cfg.label(), best.rtf, best.initMs, best.hits));
        }
        sb.append("NOTE: Probe is offline WAV only. Formal integration still needs a live-mic soak.\n");
        return sb.toString();
    }

    private static File resolveModelRoot() {
        File[] candidates = new File[] {
                new File(Environment.getExternalStorageDirectory(), MODEL_DIR_NAME),
                new File("/sdcard/" + MODEL_DIR_NAME),
                new File("/storage/emulated/0/" + MODEL_DIR_NAME),
        };
        for (File f : candidates) {
            if (f.isDirectory() && new File(f, "tokens.txt").isFile()) return f;
        }
        return null;
    }

    private static List<File> listWavs(File dir) {
        File[] files = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".wav")) out.add(f);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static void writeReportFile(File root, String text) {
        File out = new File(root, "last-report.txt");
        try (OutputStreamWriter w = new OutputStreamWriter(
                new BufferedOutputStream(new FileOutputStream(out)), UTF8)) {
            w.write(text);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + out + ": " + e.getMessage());
        }
    }

    private static long nativeHeapKb() {
        return Debug.getNativeHeapAllocatedSize() / 1024L;
    }

    /** Minimal PCM16 LE mono WAV reader (ignores non-data chunks). */
    static float[] readWavPcm16Mono(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] hdr = new byte[12];
            in.readFully(hdr);
            if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F'
                    || hdr[8] != 'W' || hdr[9] != 'A' || hdr[10] != 'V' || hdr[11] != 'E') {
                throw new IOException("not a RIFF/WAVE: " + file.getName());
            }
            int channels = 0;
            int bits = 0;
            int rate = 0;
            byte[] data = null;
            while (data == null) {
                byte[] chunkHdr = new byte[8];
                int n = in.read(chunkHdr);
                if (n < 8) throw new IOException("truncated WAV: " + file.getName());
                String id = new String(chunkHdr, 0, 4, Charset.forName("US-ASCII"));
                int size = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[size];
                    in.readFully(fmt);
                    ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    int format = bb.getShort() & 0xffff;
                    channels = bb.getShort() & 0xffff;
                    rate = bb.getInt();
                    bb.getInt(); // byte rate
                    bb.getShort(); // block align
                    bits = bb.getShort() & 0xffff;
                    if (format != 1) throw new IOException("only PCM supported: " + file.getName());
                    if (size % 2 == 1) in.read(); // pad
                } else if ("data".equals(id)) {
                    data = new byte[size];
                    in.readFully(data);
                    if (size % 2 == 1) in.read();
                } else {
                    long skipped = in.skip(size + (size % 2));
                    if (skipped < size) throw new IOException("skip failed in " + file.getName());
                }
            }
            if (bits != 16) throw new IOException("need 16-bit PCM: " + file.getName());
            if (rate != SAMPLE_RATE) {
                Log.w(TAG, file.getName() + " sampleRate=" + rate + " (expected " + SAMPLE_RATE + ")");
            }
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int frames = data.length / (2 * Math.max(1, channels));
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += bb.getShort();
                mono[i] = (sum / (float) channels) / 32768.0f;
            }
            return mono;
        }
    }

    private static final class Config {
        final String precision;
        final int threads;
        final boolean int8;

        Config(String precision, int threads, boolean int8) {
            this.precision = precision;
            this.threads = threads;
            this.int8 = int8;
        }

        String label() { return precision + " x" + threads + "t"; }
    }

    private static final class Row {
        final Config cfg;
        boolean ok;
        String error;
        long initMs;
        double audioSec;
        long decodeWallMs;
        double rtf;
        int decodeCalls;
        int hits;
        long rssAfterInitKb;
        long rssPeakKb;
        long rssDeltaKb;

        Row(Config cfg) { this.cfg = cfg; }

        String formatBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("--- ").append(cfg.label()).append(" ---\n");
            if (!ok) {
                sb.append("ok=false error=").append(error).append('\n');
                return sb.toString();
            }
            sb.append(String.format(Locale.US, "ok=true\n"));
            sb.append(String.format(Locale.US, "init_ms=%d\n", initMs));
            sb.append(String.format(Locale.US, "audio_sec=%.3f\n", audioSec));
            sb.append(String.format(Locale.US, "decode_wall_ms=%d\n", decodeWallMs));
            sb.append(String.format(Locale.US, "rtf=%.3f\n", rtf));
            sb.append(String.format(Locale.US, "decode_calls=%d\n", decodeCalls));
            sb.append(String.format(Locale.US, "hits=%d\n", hits));
            sb.append(String.format(Locale.US, "rss_after_init_kb=%d\n", rssAfterInitKb));
            sb.append(String.format(Locale.US, "rss_peak_kb=%d\n", rssPeakKb));
            sb.append(String.format(Locale.US, "rss_delta_kb=%d\n", rssDeltaKb));
            if (rtf >= 1.0) sb.append("flag=BACKLOG_RISK\n");
            else if (rtf >= 0.50) sb.append("flag=THIN_HEADROOM\n");
            else sb.append("flag=OK\n");
            return sb.toString();
        }
    }
}
