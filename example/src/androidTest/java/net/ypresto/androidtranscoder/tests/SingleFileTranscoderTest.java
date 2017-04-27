package net.ypresto.androidtranscoder.tests;

import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import net.ypresto.androidtranscoder.MediaTranscoder;
import net.ypresto.androidtranscoder.engine.TimeLine;
import net.ypresto.androidtranscoder.format.Android16By9FormatStrategy;
import net.ypresto.androidtranscoder.format.Android720pFormatStrategy;
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class SingleFileTranscoderTest {
    private static final String TAG = "JUnitTranscoder";
    private String inputFileName1;
    private String inputFileName2;
    private String outputFileName;
    private String status = "not started";

    MediaTranscoder.Listener listener = new MediaTranscoder.Listener() {
        @Override
        public void onTranscodeProgress(double progress) {
            Log.d(TAG, "Progress " + progress);
        }
        @Override
        public void onTranscodeCompleted() {
            Log.d(TAG, "Complete");
            status = "complete";
        }
        @Override
        public void onTranscodeCanceled() {
            status = "canceled";
        }
        @Override
        public void onTranscodeFailed(Exception e) {
            assertEquals("onTranscodeFailed", "none", e + Log.getStackTraceString(e));
        }
    };


    @Before
    public void retrieveVideo ()  {
        inputFileName1 = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null) + "/input1.mp4";
        inputFileName2 = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null) + "/input2.mp4";
        outputFileName = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null) + "/output.mp4";
        cleanup(inputFileName1);
        cleanup(inputFileName2);
        cleanup(outputFileName);
        try {
            InputStream in = InstrumentationRegistry.getContext().getResources().openRawResource(net.ypresto.androidtranscoder.example.test.R.raw.poolcleaner);
            OutputStream out = new FileOutputStream(inputFileName1);
            copyFile(in, out);
            in.close();
            out.close();
        } catch(IOException e) {
            assertEquals("Exception on file copy", "none", e + Log.getStackTraceString(e));
        }
        try {
            InputStream in = InstrumentationRegistry.getContext().getResources().openRawResource(net.ypresto.androidtranscoder.example.test.R.raw.frogs);
            OutputStream out = new FileOutputStream(inputFileName2);
            copyFile(in, out);
            in.close();
            out.close();
        } catch(IOException e) {
            assertEquals("Exception on file copy", "none", e + Log.getStackTraceString(e));
        }
    }
/*
    @Test
    public void TranscodeToMono() {
        runTest(new Transcode() {
            @Override
            public void run() throws IOException, InterruptedException, ExecutionException {
                ParcelFileDescriptor in = ParcelFileDescriptor.open(new File(inputFileName), ParcelFileDescriptor.MODE_READ_ONLY);
                (MediaTranscoder.getInstance().transcodeVideo(
                    in.getFileDescriptor(), outputFileName,
                    MediaFormatStrategyPresets.createAndroid720pStrategyMono(),
                    listener)
                ).get();

            }
        });
    }
    */
    @Test
    public void TranscodeTwoFiles() {
        runTest(new Transcode() {
            @Override
            public void run() throws IOException, InterruptedException, ExecutionException {
                ParcelFileDescriptor in1 = ParcelFileDescriptor.open(new File(inputFileName1), ParcelFileDescriptor.MODE_READ_ONLY);
                ParcelFileDescriptor in2 = ParcelFileDescriptor.open(new File(inputFileName2), ParcelFileDescriptor.MODE_READ_ONLY);
                TimeLine timeline = new TimeLine()
                        .addChannel("A", in1.getFileDescriptor())
                        .addChannel("B", in1.getFileDescriptor())
                        .addChannel("C", in1.getFileDescriptor())
                        .addAudioOnlyChannel("D", in2.getFileDescriptor())
                        .createSegment()
                            .output("C")
                            .output("D")
                        .timeLine().createSegment()
                            .seek("A", 1000)
                            .duration(1500)
                            .output("A")
                            .output("D")
                        .timeLine().createSegment()
                            .seek("B", 1000)
                            .output("B")
                            .duration(1500)
                            .output("D")
                        .timeLine();
                (MediaTranscoder.getInstance().transcodeVideo(
                        timeline, outputFileName,
                        MediaFormatStrategyPresets.createAndroid16x9Strategy720P(Android16By9FormatStrategy.AUDIO_BITRATE_AS_IS, Android16By9FormatStrategy.AUDIO_CHANNELS_AS_IS),
                        listener)
                ).get();

            }
        });
    }
    public interface Transcode {
        void run () throws IOException, InterruptedException, ExecutionException;
    }
    private void runTest(Transcode callback) {
        try {
            callback.run();
        } catch(IOException e) {
            assertEquals("Exception on Transcode", "none", e + Log.getStackTraceString(e));
        } catch(InterruptedException e) {
            assertEquals("Exception on Transcode", "none", e + Log.getStackTraceString(e));
        } catch(ExecutionException e) {
            assertEquals("Exception on Transcode", "none", e + Log.getStackTraceString(e));

        }
        File file =new File(outputFileName);
        Log.d(TAG, " output file size " + file.length());
        assertEquals("Completed", status, "complete");
    }


    // Helpers
    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
    private void cleanup(String fileName) {
        (new File(fileName)).delete();
    }
}
