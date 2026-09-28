package com.lite.rec;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.MediaStore;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Một phiên ghi: video H.264 (encoder phần cứng, nhận thẳng Surface) + âm thanh hệ thống AAC,
 * mux trực tiếp vào Movies/ qua MediaStore. Không có bước xử lý ảnh trung gian nào.
 */
final class Recorder {
    // ---- Chất lượng (giống repo cũ: cạnh dài 1920, 14 Mbps VBR, 30 fps, keyframe 1s) ----
    static final int LONG_SIDE = 2160;
    static final int FPS = 30;
    static final int VIDEO_BPS = 16_000_000;
    static final int KEY_INTERVAL_S = 1;
    // ---- Âm thanh ----
    static final int RATE = 48000;
    static final int CH = 2;
    static final int AUDIO_BPS = 128_000;

    interface Done { void onDone(String message); }

    private static final class Pend {
        boolean video; byte[] d; long pts; int flags;
    }

    private final Context ctx;
    private final MediaProjection proj;
    private final int w, h;

    private MediaCodec venc, aenc;
    private Surface vSurface;
    private AudioRecord ar;
    private volatile boolean hasAudio;
    private boolean audioRequested;
    private volatile String audioErr = "";
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private MediaMuxer mux;
    private String fileName = "";
    private Thread vThread, aThread;
    private volatile boolean stopReq;
    private volatile long stopDeadline = Long.MAX_VALUE;
    private volatile int peak;

    private final Object lock = new Object();
    private final ArrayList<Pend> pend = new ArrayList<>();
    private final MediaCodec.BufferInfo mi = new MediaCodec.BufferInfo();
    private MediaFormat vFmt, aFmt;
    private int vTrack = -1, aTrack = -1;
    private boolean muxOn, muxDead;
    private long base = -1, lastV = -1, lastA = -1;
    private int videoSamples;
    private String muxErr = "";

    Recorder(Context ctx, MediaProjection proj, int w, int h) {
        this.ctx = ctx;
        this.proj = proj;
        this.w = w;
        this.h = h;
    }

    // ------------------------------------------------------------------ start

    Surface start() throws Exception {
        try {
            ContentResolver cr = ctx.getContentResolver();
            fileName = "REC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert null");
            pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IllegalStateException("openFileDescriptor null");
            mux = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            try {
                venc = makeVideoEncoder(true);
            } catch (Exception e) {
                venc = makeVideoEncoder(false);
            }
            vSurface = venc.createInputSurface();
            venc.start();

            ar = makeAudioRecord();
            if (ar != null) {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CH);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BPS);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aenc.start();
                hasAudio = true;
                audioRequested = true;
            }

            vThread = new Thread(this::videoLoop, "rec-v");
            vThread.start();
            if (hasAudio) {
                aThread = new Thread(this::audioLoop, "rec-a");
                aThread.start();
            }
            return vSurface;
        } catch (Exception e) {
            abort();
            throw e;
        }
    }

    private MediaCodec makeVideoEncoder(boolean high) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BPS);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_INTERVAL_S);
        f.setInteger("max-fps-to-encoder", FPS); // giới hạn số frame đưa vào encoder (key lạ sẽ bị bỏ qua)
        if (high) f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            c.release();
            throw e;
        }
        return c;
    }

    private AudioRecord makeAudioRecord() {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioErr = "chưa cấp quyền ghi âm";
                return null;
            }
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 4, RATE * CH * 2 / 2); // >= 0.5s để chịu được lúc CPU bị game chiếm
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(size)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                audioErr = "AudioRecord không khởi tạo được";
                return null;
            }
            return r;
        } catch (Throwable t) {
            audioErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    // ------------------------------------------------------------------ video

    private void videoLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int i = venc.dequeueOutputBuffer(bi, 20_000);
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) { vFmt = venc.getOutputFormat(); }
                } else if (i >= 0) {
                    ByteBuffer b = venc.getOutputBuffer(i);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && b != null) writeSample(true, b, bi);
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    venc.releaseOutputBuffer(i, false);
                    if (eos) break;
                } else if (i == MediaCodec.INFO_TRY_AGAIN_LATER && System.nanoTime() > stopDeadline) {
                    break;
                }
            }
        } catch (Exception e) {
            muxErr = "video: " + e;
        }
    }

    // ------------------------------------------------------------------ audio

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] buf = new byte[8192];
        long frames = 0, anchor = 0;
        try {
            ar.startRecording();
            anchor = System.nanoTime() / 1000;
            while (!stopReq) {
                int n = ar.read(buf, 0, buf.length); // blocking
                if (n < 0) { audioErr = "AudioRecord.read lỗi " + n; break; }
                if (n == 0) continue;
                trackPeak(buf, n);
                feedAudio(buf, n, anchor + frames * 1_000_000L / RATE);
                frames += n / (2 * CH);
                drainAudio(false);
            }
        } catch (Throwable t) {
            audioErr = t.toString();
        }
        try { ar.stop(); } catch (Exception ignored) { }
        try { ar.release(); } catch (Exception ignored) { }
        try {
            long pts = anchor + frames * 1_000_000L / RATE;
            for (int tries = 0; tries < 50; tries++) {
                int idx = aenc.dequeueInputBuffer(20_000);
                if (idx >= 0) {
                    aenc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drainAudio(false);
            }
            drainAudio(true);
        } catch (Throwable ignored) { }
    }

    private void trackPeak(byte[] b, int n) {
        int p = peak;
        for (int i = 0; i + 1 < n; i += 8) {
            int s = (short) ((b[i + 1] << 8) | (b[i] & 0xFF));
            if (s < 0) s = -s;
            if (s > p) p = s;
        }
        peak = p;
    }

    private void feedAudio(byte[] d, int len, long pts) {
        int off = 0, tries = 0;
        while (off < len) {
            int idx = aenc.dequeueInputBuffer(10_000);
            if (idx < 0) {
                drainAudio(false);
                if (++tries > 50) return; // encoder kẹt: bỏ chunk này, không treo
                continue;
            }
            ByteBuffer ib = aenc.getInputBuffer(idx);
            ib.clear();
            int n = Math.min(ib.remaining(), len - off);
            ib.put(d, off, n);
            aenc.queueInputBuffer(idx, 0, n, pts + (long) off / (2 * CH) * 1_000_000L / RATE, 0);
            off += n;
        }
    }

    private final MediaCodec.BufferInfo abi = new MediaCodec.BufferInfo();

    private void drainAudio(boolean waitEos) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true) {
            int i = aenc.dequeueOutputBuffer(abi, waitEos ? 20_000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitEos || System.nanoTime() > deadline) return;
                continue;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) { aFmt = aenc.getOutputFormat(); }
                continue;
            }
            if (i < 0) continue;
            ByteBuffer ob = aenc.getOutputBuffer(i);
            if ((abi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) abi.size = 0;
            if (abi.size > 0 && ob != null) writeSample(false, ob, abi);
            boolean eos = (abi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            aenc.releaseOutputBuffer(i, false);
            if (eos) return;
        }
    }

    // ------------------------------------------------------------------ mux

    private void writeSample(boolean video, ByteBuffer b, MediaCodec.BufferInfo bi) {
        synchronized (lock) {
            if (muxDead) return;
            if (muxOn) {
                put(video, b, bi.offset, bi.size, bi.presentationTimeUs, bi.flags);
                return;
            }
            Pend p = new Pend();
            p.video = video;
            p.pts = bi.presentationTimeUs;
            p.flags = bi.flags;
            p.d = new byte[bi.size];
            b.position(bi.offset);
            b.limit(bi.offset + bi.size);
            b.get(p.d);
            pend.add(p);
            // Nếu chờ format audio quá lâu thì bỏ audio, mux video trước (tránh dồn RAM)
            if (hasAudio && aFmt == null && pend.size() > 300) hasAudio = false;
            tryStartMux();
        }
    }

    private void tryStartMux() {
        if (vFmt == null || (hasAudio && aFmt == null)) return;
        long firstV = -1;
        for (Pend p : pend) if (p.video) { firstV = p.pts; break; }
        if (firstV < 0) return;
        try {
            vTrack = mux.addTrack(vFmt);
            if (hasAudio) aTrack = mux.addTrack(aFmt);
            mux.start();
            muxOn = true;
            base = firstV;
        } catch (Exception e) {
            muxDead = true;
            muxErr = "mux: " + e;
            pend.clear();
            return;
        }
        Collections.sort(pend, (x, y) -> Long.compare(x.pts, y.pts));
        for (Pend p : pend) put(p.video, ByteBuffer.wrap(p.d), 0, p.d.length, p.pts, p.flags);
        pend.clear();
    }

    private void put(boolean video, ByteBuffer b, int off, int size, long pts, int flags) {
        long t = pts - base;
        if (video) {
            if (t <= lastV) t = lastV + 1;
            lastV = t;
            videoSamples++;
        } else {
            if (aTrack < 0 || t < 0) return; // audio trước frame video đầu tiên: bỏ
            if (t <= lastA) t = lastA + 1;
            lastA = t;
        }
        mi.set(off, size, t, video ? (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) : 0);
        try {
            mux.writeSampleData(video ? vTrack : aTrack, b, mi);
        } catch (Exception e) {
            muxDead = true;
            muxErr = "write: " + e;
        }
    }

    // ------------------------------------------------------------------ stop

    void stopAsync(Done cb) {
        stopReq = true;
        stopDeadline = System.nanoTime() + 3_000_000_000L;
        new Thread(() -> cb.onDone(finish()), "rec-fin").start();
    }

    private String finish() {
        try { venc.signalEndOfInputStream(); } catch (Exception ignored) { }
        join(aThread, 2000);
        if (aThread != null && aThread.isAlive()) {
            try { ar.stop(); } catch (Exception ignored) { }
            join(aThread, 1500);
        }
        join(vThread, 3500);

        boolean ok;
        synchronized (lock) {
            ok = muxOn && !muxDead && videoSamples > 0;
            try { if (muxOn) mux.stop(); } catch (Exception e) { ok = false; muxErr = "stop: " + e; }
            try { mux.release(); } catch (Exception ignored) { }
            muxDead = true;
        }
        release();
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }

        ContentResolver cr = ctx.getContentResolver();
        try {
            if (ok) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
            } else if (uri != null) {
                cr.delete(uri, null, null);
            }
        } catch (Exception ignored) { }

        if (!ok) return "Lỗi lưu video" + (muxErr.isEmpty() ? "" : " (" + muxErr + ")");
        String a;
        if (!audioRequested) a = "KHÔNG có âm thanh (" + audioErr + ")";
        else if (!audioErr.isEmpty() && peak <= 30) a = "KHÔNG có âm thanh (" + audioErr + ")";
        else if (peak > 30) a = "có âm thanh";
        else a = "âm thanh IM LẶNG (game có thể chặn ghi âm nội bộ)";
        return "Đã lưu Movies/" + fileName + " — " + a;
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException ignored) { }
    }

    /** Dọn khi start() lỗi giữa chừng. */
    void abort() {
        stopReq = true;
        try { if (mux != null) mux.release(); } catch (Exception ignored) { }
        release();
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        try { if (uri != null) ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    private void release() {
        try { if (venc != null) { venc.stop(); } } catch (Exception ignored) { }
        try { if (venc != null) { venc.release(); } } catch (Exception ignored) { }
        try { if (aenc != null) { aenc.stop(); } } catch (Exception ignored) { }
        try { if (aenc != null) { aenc.release(); } } catch (Exception ignored) { }
        try { if (vSurface != null) vSurface.release(); } catch (Exception ignored) { }
        try { if (ar != null) ar.release(); } catch (Exception ignored) { }
    }
}
