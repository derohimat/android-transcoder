package net.ypresto.androidtranscoder.tests;

import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import net.ypresto.androidtranscoder.MediaTranscoder;
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
    private String inputFileName;
    private String outputFileName;
    private String status = "not started";

    @Before
    public void retrieveVideo ()  {
        inputFileName = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null) + "/input.mp4";
        outputFileName = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null) + "/output.mp4";
        cleanup(inputFileName);
        cleanup(outputFileName);
        try {
            InputStream in = InstrumentationRegistry.getContext().getResources().openRawResource(net.ypresto.androidtranscoder.example.test.R.raw.sample);
            OutputStream out = new FileOutputStream(inputFileName);
            copyFile(in, out);
            in.close();
            out.close();
        } catch(IOException e) {
            assertEquals("Exception on file copy", "none", e + Log.getStackTraceString(e));
        }
    }

    @Test
    public void Transcode() {
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

        try {

            ParcelFileDescriptor in = ParcelFileDescriptor.open(new File(inputFileName), ParcelFileDescriptor.MODE_READ_ONLY);
            (MediaTranscoder.getInstance().transcodeVideo(in.getFileDescriptor(), outputFileName,
                    MediaFormatStrategyPresets.createAndroid720pStrategy(), listener)).get();
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
