/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import net.ypresto.androidtranscoder.format.MediaFormatStrategy;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal engine, do not use this directly.
 */
// TODO: treat encrypted data
public class MediaTranscoderEngine {
    private static final String TAG = "MediaTranscoderEngine";
    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private LinkedHashMap<String, FileDescriptor> mInputFileDescriptor;
    private TrackTranscoder mVideoTrackTranscoder;
    private TrackTranscoder mAudioTrackTranscoder;
    private LinkedHashMap<String, MediaExtractor> mExtractor;
    private MediaMuxer mMuxer;
    private volatile double mProgress;
    private ProgressCallback mProgressCallback;
    private long mDurationUs;


    /**
     * Do not use this constructor unless you know what you are doing.
     */
    public MediaTranscoderEngine() {
        mInputFileDescriptor = new LinkedHashMap<String, FileDescriptor>();
        mExtractor = new LinkedHashMap<String, MediaExtractor>();
    }

    public void setDataSource(FileDescriptor fileDescriptor) {
        setDataSource(fileDescriptor, "default");
    }
    public void setDataSource(FileDescriptor fileDescriptor, String channelName) {
        mInputFileDescriptor.put(channelName, fileDescriptor);
    }

    public ProgressCallback getProgressCallback() {
        return mProgressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
    }

    /**
     * NOTE: This method is thread safe.
     */
    public double getProgress() {
        return mProgress;
    }

    /**
     * Run video transcoding. Blocks current thread.
     * Audio data will not be transcoded; original stream will be wrote to output file.
     *
     * @param outputPath     File path to output transcoded video file.
     * @param formatStrategy Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcodeVideo(String outputPath, MediaFormatStrategy formatStrategy) throws IOException, InterruptedException {
        OutputSegment.create().addChannel("default");
        if (outputPath == null) {
            throw new NullPointerException("Output path cannot be null.");
        }
        if (mInputFileDescriptor == null) {
            throw new IllegalStateException("Data source is not set.");
        }
        try {
            // NOTE: use single extractor to keep from running out audio track fast.
            for (Map.Entry<String, FileDescriptor> entry : mInputFileDescriptor.entrySet()) {
                MediaExtractor m = new MediaExtractor();
                m.setDataSource(entry.getValue());
                mExtractor.put(entry.getKey(), m);
            }
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMetadata();
            setupTrackTranscoders(formatStrategy);
            runPipelines();
            mMuxer.stop();
        } finally {
            try {
                if (mVideoTrackTranscoder != null) {
                    mVideoTrackTranscoder.release();
                    mVideoTrackTranscoder = null;
                }
                if (mAudioTrackTranscoder != null) {
                    mAudioTrackTranscoder.release();
                    mAudioTrackTranscoder = null;
                }
                for (Map.Entry<String, MediaExtractor> entry : mExtractor.entrySet()) {
                    entry.getValue().release();
                }
            } catch (RuntimeException e) {
                // Too fatal to make alive the app, because it may leak native resources.
                //noinspection ThrowFromFinallyBlock
                throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
            }
            try {
                if (mMuxer != null) {
                    mMuxer.release();
                    mMuxer = null;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to release muxer.", e);
            }
        }
    }

    private void setupMetadata() throws IOException {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(mInputFileDescriptor.entrySet().iterator().next().getValue());

        String rotationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            mMuxer.setOrientationHint(Integer.parseInt(rotationString));
        } catch (NumberFormatException e) {
            // skip
        }

        // TODO: parse ISO 6709
        // String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
        // mMuxer.setLocation(Integer.getInteger(rotationString, 0));

        try {
            mDurationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
        } catch (NumberFormatException e) {
            mDurationUs = -1;
        }
        Log.d(TAG, "Duration (us): " + mDurationUs);
    }

    private void setupTrackTranscoders(MediaFormatStrategy formatStrategy) {

        // Find the first vidoe and audio track to establish an interim output format
        MediaFormat videoOutputFormat = null;
        MediaFormat audioOutputFormat = null;
        MediaExtractorUtils.TrackResult trackResult = null;
        for (Map.Entry<String, MediaExtractor> entry : mExtractor.entrySet()) {
            trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(entry.getValue());
            MediaExtractor extractor = mExtractor.get(entry.getKey());
            if (videoOutputFormat == null && trackResult.mVideoTrackFormat != null) {
                videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
            }
            if (audioOutputFormat == null && trackResult.mAudioTrackFormat != null) {
                audioOutputFormat = formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat);
            }
            extractor.selectTrack(trackResult.mVideoTrackIndex);
            extractor.selectTrack(trackResult.mAudioTrackIndex);
        }
        if (videoOutputFormat == null && audioOutputFormat == null) {
            throw new InvalidOutputFormatException("MediaFormatStrategy returned pass-through for both video and audio. No transcoding is necessary.");
        }
        QueuedMuxer queuedMuxer = new QueuedMuxer(mMuxer, new QueuedMuxer.Listener() {
            @Override
            public void onDetermineOutputFormat() {
                MediaFormatValidator.validateVideoOutputFormat(mVideoTrackTranscoder.getDeterminedFormat());
                MediaFormatValidator.validateAudioOutputFormat(mAudioTrackTranscoder.getDeterminedFormat());
            }
        });

        if (videoOutputFormat == null && trackResult != null) {
            mVideoTrackTranscoder = new PassThroughTrackTranscoder(mExtractor.entrySet().iterator().next().getValue(), trackResult.mVideoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
        } else {
            mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, videoOutputFormat, queuedMuxer);
        }
        mVideoTrackTranscoder.setupEncoder();

        if (audioOutputFormat == null) {
            mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor.entrySet().iterator().next().getValue(), trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
        } else {
            mAudioTrackTranscoder = new AudioTrackTranscoder(mExtractor,  audioOutputFormat, queuedMuxer);
        }
        mAudioTrackTranscoder.setupEncoder();
    }

    private void runPipelines() {
        long loopCount = 0;
        if (mDurationUs <= 0) {
            double progress = PROGRESS_UNKNOWN;
            mProgress = progress;
            if (mProgressCallback != null) mProgressCallback.onProgress(progress); // unknown
        }
        for (OutputSegment segment : OutputSegment.getList()) {
            mAudioTrackTranscoder.setupDecoder(segment);
            mVideoTrackTranscoder.setupDecoder(segment);
            while (!(mVideoTrackTranscoder.isSegmentFinished() && mAudioTrackTranscoder.isSegmentFinished())) {
                boolean stepped = mVideoTrackTranscoder.stepPipeline(segment)
                        || mAudioTrackTranscoder.stepPipeline(segment);
                loopCount++;
                if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                    double videoProgress = mVideoTrackTranscoder.isSegmentFinished() ? 1.0 : Math.min(1.0, (double) mVideoTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                    double audioProgress = mAudioTrackTranscoder.isSegmentFinished() ? 1.0 : Math.min(1.0, (double) mAudioTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                    double progress = (videoProgress + audioProgress) / 2.0;
                    mProgress = progress;
                    if (mProgressCallback != null) mProgressCallback.onProgress(progress);
                }
                if (!stepped) {
                    try {
                        Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                    } catch (InterruptedException e) {
                        // nothing to do
                    }
                }
            }
            mVideoTrackTranscoder.releaseDecoder(segment);
            mAudioTrackTranscoder.releaseDecoder(segment);
        }
    }

    public interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
