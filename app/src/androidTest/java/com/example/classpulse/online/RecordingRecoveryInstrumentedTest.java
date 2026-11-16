package com.example.classpulse.online;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingRecoveryInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // Generate non-personal audio locally. No credentials, recordings or network are required.
    private void audio(File target) throws Exception {
        target.getParentFile().mkdirs();
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 48000);
        MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaMuxer muxer = new MediaMuxer(target.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false, inputDone = false, outputDone = false;
        int samples = 0, track = -1;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 15000;
            while (!outputDone) {
                assertTrue("encoder timeout", System.currentTimeMillis() < deadline);
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN);
                        int count = Math.min(Math.min(1024, buffer.remaining() / 2), 44100 - samples);
                        for (int i = 0; i < count; i++) buffer.putShort((short) (Math.sin((samples + i) * 440 * 2 * Math.PI / 44100) * 1000));
                        codec.queueInputBuffer(index, 0, count * 2, samples * 1000000L / 44100,
                                count == 0 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        samples += count;
                        inputDone = count == 0;
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true;
                } else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                        muxer.writeSampleData(track, codec.getOutputBuffer(index), info);
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
            }
        } finally {
            codec.stop(); codec.release();
            if (started) muxer.stop();
            muxer.release();
        }
    }

    private void recover(String uid, String sid) throws Exception {
        LectureRecording.recover(context, uid, sid);
        long deadline = System.currentTimeMillis() + 15000;
        while (LectureRecording.isFinalizing(uid, sid) && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertFalse("recovery completed", LectureRecording.isFinalizing(uid, sid));
    }

    @Test public void combinesCompletedChunksAndRecoversPrefixBeforeDamagedTail() throws Exception {
        File output = new File(context.getCacheDir(), OnlineJson.id() + ".m4a");
        try {
            audio(LectureSegments.part(output, 0));
            audio(LectureSegments.part(output, 1));
            try (FileOutputStream tail = new FileOutputStream(LectureSegments.part(output, 2))) { tail.write(new byte[]{1, 2, 3}); }
            LectureSegments.Result result = LectureSegments.assemble(output);
            assertTrue(result.partial);
            assertTrue("both completed seconds preserved", result.durationMs >= 1900);
            assertTrue(LectureRecording.playable(output, true));
            assertTrue("sources retained until journal commit", LectureSegments.part(output, 0).exists());
        } finally { LectureSegments.cleanCompleted(output); output.delete(); }
    }

    @Test public void unusedNextFileIsNotReportedAsLostAudioOnNormalStop() throws Exception {
        File output = new File(context.getCacheDir(), OnlineJson.id() + ".m4a");
        try {
            audio(LectureSegments.part(output, 0));
            assertTrue(LectureSegments.part(output, 1).createNewFile());
            LectureSegments.Result result = LectureSegments.assemble(output);
            assertFalse(result.partial);
            assertTrue(result.durationMs >= 950);
            assertTrue(LectureRecording.playable(output, true));
        } finally { LectureSegments.cleanCompleted(output); output.delete(); }
    }

    @Test public void pendingJournalRestoresAudioAndDoesNotReplacePreviousOnUnrecoverableInput() throws Exception {
        String uid = "recovery-test-" + OnlineJson.id(), sid = OnlineJson.id(), key = uid + "/" + sid;
        String oldId = OnlineJson.id(), pendingId = OnlineJson.id();
        File old = LectureRecording.file(context, uid, sid, oldId), output = LectureRecording.file(context, uid, sid, pendingId);
        SharedPreferences prefs = context.getSharedPreferences("lecture_recordings", 0);
        try {
            audio(old);
            prefs.edit().putString(key, oldId).putLong(key + "/startedAt", 100)
                    .putString(key + "/pending", pendingId).putLong(key + "/pendingStartedAt", 200).commit();
            try (FileOutputStream broken = new FileOutputStream(LectureSegments.part(output, 0))) { broken.write(0); }
            recover(uid, sid);
            assertEquals(oldId, LectureRecording.saved(context, uid, sid).id);
            assertFalse(LectureRecording.hasPending(context, uid, sid));
            assertTrue(LectureRecording.savedNotice(context, uid, sid).contains("이전에 저장한 녹음"));
            audio(LectureSegments.part(output, 0));
            prefs.edit().putString(key + "/pending", pendingId).putLong(key + "/pendingStartedAt", 200).commit();
            recover(uid, sid);
            assertEquals(pendingId, LectureRecording.saved(context, uid, sid).id);
            assertEquals(200, LectureRecording.saved(context, uid, sid).startedAt);
            assertFalse(LectureRecording.hasPending(context, uid, sid));
            assertTrue(old.exists());
            // Simulate process loss after publishing the M4A but before journal cleanup.
            prefs.edit().putString(key + "/pending", pendingId).putLong(key + "/pendingStartedAt", 200).commit();
            recover(uid, sid);
            assertEquals(pendingId, LectureRecording.saved(context, uid, sid).id);
            assertFalse(LectureRecording.hasPending(context, uid, sid));
        } finally {
            prefs.edit().remove(key).remove(key + "/startedAt").remove(key + "/pending")
                    .remove(key + "/pendingStartedAt").remove(key + "/notice").commit();
            LectureSegments.cleanCompleted(output); output.delete(); old.delete(); old.getParentFile().delete();
        }
    }
}
