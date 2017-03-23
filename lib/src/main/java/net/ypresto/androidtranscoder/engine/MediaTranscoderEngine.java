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
import java.util.LinkedHashMap;
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
    private FileDescriptor mFirstFileDescriptorWithVideo;
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
        mExtractor = new LinkedHashMap<String, MediaExtractor>();
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
     * @param timeLine                      Time line of segments
     * @param outputPath                    File path to output transcoded video file.
     * @param formatStrategy                Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcodeVideo(TimeLine timeLine, String outputPath, MediaFormatStrategy formatStrategy) throws IOException, InterruptedException {

        if (outputPath == null) {
            throw new NullPointerException("Output path cannot be null.");
        }
        if (mFirstFileDescriptorWithVideo == null) {
            throw new IllegalStateException("Data source is not set.");
        }
        try {
             mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMetadata();
            setupTrackTranscoders(timeLine, formatStrategy);
            runPipelines(timeLine);
            mMuxer.stop();
        } finally {
            try {
                if (mVideoTrackTranscoder != null) {
                    //mVideoTrackTranscoder.release();
                    mVideoTrackTranscoder = null;
                }
                if (mAudioTrackTranscoder != null) {
                    //mAudioTrackTranscoder.release();
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
        mediaMetadataRetriever.setDataSource(mFirstFileDescriptorWithVideo);

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

    /**
     * Setup MediaExtractors for ever possible case in each output segment but defer connecting
     * up the decoders until they are needed.  There is a limit based on device resources as to
     * how many decoders can run at the same time and this reduced to absolute minimum needed.
     *
     * Invoke the extractor to get track information which will be used to determine high level
     * output output format details. Setup a queuedMuxer which delays Muxing until the decoder has
     * enough information to call it's setOutputFormat method and set the detailed output format.
     *
     * @param timeLine
     * @param formatStrategy
     * @throws IOException
     */
    private void setupTrackTranscoders(TimeLine timeLine, MediaFormatStrategy formatStrategy) throws IOException {

        // Setup all extractors for all segments, finding the first video and audio track to establish an interim output format
        MediaFormat videoOutputFormat = null;
        MediaFormat audioOutputFormat = null;
        MediaExtractorUtils.TrackResult trackResult = null;
        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : timeLine.getChannels().entrySet()) {
            if (videoOutputFormat != null || audioOutputFormat != null) {
                FileDescriptor fileDescriptor = inputChannelEntry.getValue().mInputFileDescriptor;
                MediaExtractor mediaExtractor = new MediaExtractor();
                try {
                    mediaExtractor.setDataSource(fileDescriptor);
                } catch (IOException e) {
                    Log.w(TAG, "Transcode failed: input file (fd: " + fileDescriptor.toString() + ") not found");
                    throw e;
                }
                trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mediaExtractor);
                if (videoOutputFormat == null && trackResult.mVideoTrackFormat != null) {
                    videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
                    mediaExtractor.selectTrack(trackResult.mVideoTrackIndex);
                    mExtractor.put(inputChannelEntry.getKey(), mediaExtractor);
                    mFirstFileDescriptorWithVideo = fileDescriptor;
                }
                if (audioOutputFormat == null && trackResult.mAudioTrackFormat != null) {
                    audioOutputFormat = formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat);
                    mediaExtractor.selectTrack(trackResult.mVideoTrackIndex);
                    mExtractor.put(inputChannelEntry.getKey(), mediaExtractor);
                }
            }
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
            mVideoTrackTranscoder = new PassThroughTrackTranscoder(mExtractor.entrySet().iterator().next().getValue(),
                    trackResult.mVideoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
        } else {
            mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, videoOutputFormat, queuedMuxer);
        }
        mVideoTrackTranscoder.setupEncoder();

        if (audioOutputFormat == null) {
            mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor.entrySet().iterator().next().getValue(),
                    trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
        } else {
            mAudioTrackTranscoder = new AudioTrackTranscoder(mExtractor,  audioOutputFormat, queuedMuxer);
        }
        mAudioTrackTranscoder.setupEncoder();
    }

    private void runPipelines(TimeLine timeLine) throws IOException {
        long loopCount = 0;
        if (mDurationUs <= 0) {
            double progress = PROGRESS_UNKNOWN;
            mProgress = progress;
            if (mProgressCallback != null)
                mProgressCallback.onProgress(progress); // unknown
        }
        for (TimeLine.Segment outputSegment : timeLine.getSegments()) {
            mAudioTrackTranscoder.setupDecoders(outputSegment, mExtractor);
            mVideoTrackTranscoder.setupDecoders(outputSegment, mExtractor);
            while (!(mVideoTrackTranscoder.isSegmentFinished() && mAudioTrackTranscoder.isSegmentFinished())) {
                boolean stepped = mVideoTrackTranscoder.stepPipeline(outputSegment) || mAudioTrackTranscoder.stepPipeline(outputSegment);
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

        }
        mVideoTrackTranscoder.releaseDecoders();
        mAudioTrackTranscoder.releaseDecoders();
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
