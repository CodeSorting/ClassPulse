package com.example.classpulse.online;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Completed AAC chunks survive loss of the recorder process. Never modify the source chunks. */
final class LectureSegments {
    static File directory(File output) { return new File(output.getPath() + ".segments"); }

    static File part(File output, int index) throws IOException {
        File directory = directory(output);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("recording directory");
        return new File(directory, String.format(Locale.ROOT, "%06d.m4a", index));
    }

    static final class Result {
        final long durationMs;
        final boolean partial;
        Result(long durationMs, boolean partial) { this.durationMs = durationMs; this.partial = partial; }
    }

    static Result assemble(File output) throws IOException {
        File[] parts = directory(output).listFiles((dir, name) -> name.matches("[0-9]{6}\\.m4a"));
        if (parts == null || parts.length == 0) return new Result(0, true);
        Arrays.sort(parts, Comparator.comparing(File::getName));
        File temporary = new File(output.getPath() + ".assembling");
        MediaMuxer muxer = null;
        MediaFormat format = null;
        int track = -1;
        long offsetUs = 0;
        boolean partial = false, finished = false;
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        try {
            for (int index = 0; index < parts.length; index++) {
                File part = parts[index];
                MediaExtractor extractor = new MediaExtractor();
                try {
                    // An unused next-output file is empty. An interrupted MP4 may lack its index.
                    if (part.length() == 0) { partial = index < parts.length - 1; break; }
                    try { extractor.setDataSource(part.getAbsolutePath()); }
                    catch (IOException | RuntimeException damaged) { partial = true; break; }
                    int audio = -1;
                    for (int i = 0; i < extractor.getTrackCount(); i++)
                        if ("audio/mp4a-latm".equals(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME))) audio = i;
                    if (audio < 0) { partial = true; break; }
                    MediaFormat current = extractor.getTrackFormat(audio);
                    extractor.selectTrack(audio);
                    if (extractor.getSampleTime() < 0) { partial = true; break; }
                    if (muxer == null) {
                        format = current;
                        muxer = new MediaMuxer(temporary.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        track = muxer.addTrack(format);
                        muxer.start();
                    } else if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != current.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != current.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                        throw new IOException("incompatible recording segments");
                    }
                    long firstUs = extractor.getSampleTime(), lastUs = -1;
                    long frameUs = 1024L * 1000000 / current.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    while (true) {
                        buffer.clear();
                        int bytes = extractor.readSampleData(buffer, 0);
                        if (bytes < 0) break;
                        long relativeUs = extractor.getSampleTime() - firstUs;
                        if (relativeUs < 0 || relativeUs <= lastUs) throw new IOException("invalid recording timestamps");
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        info.set(0, bytes, offsetUs + relativeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        muxer.writeSampleData(track, buffer, info);
                        lastUs = relativeUs;
                        extractor.advance();
                    }
                    offsetUs += lastUs + frameUs;
                } finally { extractor.release(); }
            }
            if (muxer == null) return new Result(0, true);
            muxer.stop();
            muxer.release();
            muxer = null;
            if (!LectureRecording.playable(temporary, false)) throw new IOException("invalid assembled recording");
            if (output.exists() || !temporary.renameTo(output)) throw new IOException("cannot publish recording");
            finished = true;
            return new Result(offsetUs / 1000, partial);
        } finally {
            if (muxer != null) { try { muxer.release(); } catch (RuntimeException ignored) {} }
            if (!finished) temporary.delete();
        }
    }

    static void cleanCompleted(File output) {
        File[] files = directory(output).listFiles((dir, name) -> name.matches("[0-9]{6}\\.m4a"));
        if (files != null) for (File file : files) file.delete();
        directory(output).delete();
    }
}
