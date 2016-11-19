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

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import net.ypresto.androidtranscoder.format.MediaFormatExtraConstants;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Refer: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/ExtractDecodeEditEncodeMuxTest.java
public class VideoTrackTranscoder implements TrackTranscoder {

    /**
     * Wraps an extractor -> decoder -> output surface that corresponds to an input channel.
     * The extractor is passed in when created and the start should be called when a segment
     * is found that needs the wrapper.
     */
    private class DecoderWrapper {
        private boolean mIsExtractorEOS;
        private boolean mIsDecoderEOS;
        private boolean mDecoderStarted;
        private MediaExtractor mExtractor;
        private MediaCodec mDecoder;
        private ByteBuffer [] mDecoderInputBuffers;
        private OutputSurface mOutputSurface;
        private Integer mTrackIndex;
        DecoderWrapper(MediaExtractor mediaExtractor) {
            mExtractor = mediaExtractor;
        }
        public void start() {
            mOutputSurface = new OutputSurface();
            MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
            if (trackResult.mVideoTrackFormat != null) {
                int trackIndex = trackResult.mVideoTrackIndex;
                mTrackIndex = trackIndex;
                mExtractor.selectTrack(trackIndex);
                MediaFormat inputFormat = mExtractor.getTrackFormat(trackIndex);
                if (inputFormat.containsKey(MediaFormatExtraConstants.KEY_ROTATION_DEGREES)) {
                    // Decoded video is rotated automatically in Android 5.0 lollipop.
                    // Turn off here because we don't want to encode rotated one.
                    // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
                    inputFormat.setInteger(MediaFormatExtraConstants.KEY_ROTATION_DEGREES, 0);
                }
                mOutputSurface = new OutputSurface();
                MediaCodec decoder = null;
                try {
                    mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
                mDecoder.start();
                mDecoderStarted = true;
                mDecoderInputBuffers = decoder.getInputBuffers();
            }
        }

        public void release() {
            if (mOutputSurface != null) {
                mOutputSurface.release();
                mOutputSurface = null;
            }
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        }

    };
    LinkedHashMap<String, DecoderWrapper> mDecoderWrappers = new LinkedHashMap<String, DecoderWrapper>();

    private static final String TAG = "VideoTrackTranscoder";
    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final MediaFormat mOutputFormat;
    private final QueuedMuxer mMuxer;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mEncoder;
    private ByteBuffer[] mEncoderOutputBuffers;
    private MediaFormat mActualOutputFormat;
    private InputSurface mEncoderInputSurfaceWrapper;
    private boolean mIsEncoderEOS;
    private boolean mEncoderStarted;
    private long mWrittenPresentationTimeUs;
    private List <TextureRender> mTextureRender;

    public VideoTrackTranscoder(LinkedHashMap<String, MediaExtractor> extractors,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mOutputFormat = outputFormat;
        mMuxer = muxer;
    }

    @Override
    public void setupEncoder() {
        try {
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInputSurfaceWrapper = new InputSurface(mEncoder.createInputSurface());
        mEncoderInputSurfaceWrapper.makeCurrent();
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderOutputBuffers = mEncoder.getOutputBuffers();
    }

    @Override
    public void setupDecoder(OutputSegment.InputStream inputStream, MediaExtractor extractor) {
        DecoderWrapper decoderWrapper = new DecoderWrapper(extractor);
        mDecoderWrappers.put(inputStream.mChannel, decoderWrapper);
        decoderWrapper.start();
    }

    @Override
    public void setupTexture(OutputSegment outputSegment) {
        mTextureRender = new ArrayList<TextureRender>();
        for (OutputSegment.VideoPatch videoPatch : outputSegment.getVideoPatches()) {
            TextureRender textureRender = new TextureRender();
            OutputSurface surface1 = mDecoderWrappers.get(videoPatch.mInput1).mOutputSurface;
            OutputSurface surface2 = videoPatch.mInput2 != null ? mDecoderWrappers.get(videoPatch.mInput2).mOutputSurface : null;
            textureRender.surfaceCreated(surface1, surface2, null);
            mTextureRender.add(textureRender);
        }
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @Override
    public boolean stepPipeline(OutputSegment outputSegment) {
        boolean stepped = false;

        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) stepped = true;
        do {
            status = drainDecoders(outputSegment, 0);
            if (status != DRAIN_STATE_NONE) stepped = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);
        while (drainExtractors(outputSegment, 0) != DRAIN_STATE_NONE) stepped = true;

        return stepped;
    }
    public void signalEndOfSegments () {
        mEncoder.signalEndOfInputStream();
    }
    @Override
    public long getWrittenPresentationTimeUs() {
        return mWrittenPresentationTimeUs;
    }

    @Override
    public boolean isSegmentFinished() {
        return mIsEncoderEOS;
    }

    // TODO: CloseGuard
    @Override
    public void releaseEncoder() {
        if (mEncoderInputSurfaceWrapper != null) {
            mEncoderInputSurfaceWrapper.release();
            mEncoderInputSurfaceWrapper = null;
        }
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }
    @Override
    public void releaseDecoder(OutputSegment outputSegment) {
        for (Map.Entry<String, OutputSegment.InputChannel> entry : outputSegment.getChannels().entrySet()) {
            OutputSegment.InputChannel channel = entry.getValue();
            DecoderWrapper encoder = mDecoderWrappers.get(entry.getKey());
            if (!encoder.mIsDecoderEOS) {
                encoder.release();
            }
        }
    }

    /**
     * Drain extractors
     * @param outputSegment
     * @param timeoutUs
     * @return DRAIN_STATE_CONSUMED - pipeline has been stepped, DRAIN_STATE_NONE - could not step
     */
    private int drainExtractors(OutputSegment outputSegment, long timeoutUs) {
        boolean sampleProcessed = false;

        for (Map.Entry<String, OutputSegment.InputStream> inputStream : outputSegment.getVideoInputStreams().entrySet()) {
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(inputStream.getKey());
            if (!decoderWrapper.mIsExtractorEOS) {

                // Find out which track the extractor has samples for next
                int trackIndex = decoderWrapper.mExtractor.getSampleTrackIndex();

                // Sample is for a different track (like audio) ignore
                if (trackIndex >= 0 && trackIndex != decoderWrapper.mTrackIndex) {
                    continue;
                }

                // Get buffer index to be filled
                int result = decoderWrapper.mDecoder.dequeueInputBuffer(timeoutUs);

                // If no buffers available ignore
                if (result < 0)
                    continue;

                // If end of stream
                if (trackIndex < 0) {
                    decoderWrapper.mIsExtractorEOS = true;
                    decoderWrapper.mDecoder.queueInputBuffer(result, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    continue;
                }

                // Get the sample into the buffer
                int sampleSize = decoderWrapper.mExtractor.readSampleData(decoderWrapper.mDecoderInputBuffers[result], 0);
                boolean isKeyFrame = (decoderWrapper.mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                decoderWrapper.mDecoder.queueInputBuffer(result, 0, sampleSize, decoderWrapper.mExtractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                decoderWrapper.mExtractor.advance();
                sampleProcessed = true;
            }
        }
        return  sampleProcessed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    private int drainDecoders(OutputSegment outputSegment, long timeoutUs) {
        boolean consumed = false;
        boolean endOfSegment = false;
        int textureCount = 0;
        int eosCount = 0;

        // Go through each decoder in the segment and get it's frame into a texture
        for (Map.Entry<String, OutputSegment.InputChannel> entry : outputSegment.getChannels().entrySet()) {
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(entry.getKey());
            if (!decoderWrapper.mIsDecoderEOS &&!decoderWrapper.mOutputSurface.hasTexture()) {
                int result = decoderWrapper.mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
                switch (result) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        continue;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
                }
                consumed = true;
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mBufferInfo.size = 0;
                     decoderWrapper.mIsDecoderEOS = true;
                    endOfSegment = true;
                }
                boolean doRender = (mBufferInfo.size > 0);
                // NOTE: doRender will block if buffer (of encoder) is full.
                // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
                decoderWrapper.mDecoder.releaseOutputBuffer(result, doRender);
                if (doRender && mBufferInfo.presentationTimeUs >= entry.getValue().mInputStartTime) {
                    decoderWrapper.mOutputSurface.awaitNewImage();
                }
            }
            if (decoderWrapper.mOutputSurface.hasTexture())
                ++textureCount;
            if (decoderWrapper.mIsExtractorEOS)
                ++eosCount;
        }

        // If all textures have been accumulated draw the image and send it to the encoder
        if (!endOfSegment && (eosCount + textureCount) >= outputSegment.getChannelCount()) {
            for (TextureRender textureRender : mTextureRender) {
                textureRender.drawFrame();
            }
            mEncoderInputSurfaceWrapper.setPresentationTime(mBufferInfo.presentationTimeUs * 1000);
            mEncoderInputSurfaceWrapper.swapBuffers();
        }

        return consumed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }
    /*
    private int drainDecoderOld(long timeoutUs) {
        if (mIsDecoderEOS) return DRAIN_STATE_NONE;
        int result = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mEncoder.signalEndOfInputStream();
            mIsDecoderEOS = true;
            mBufferInfo.size = 0;
        }
        boolean doRender = (mBufferInfo.size > 0);
        // NOTE: doRender will block if buffer (of encoder) is full.
        // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
        mDecoder.releaseOutputBuffer(result, doRender);
        if (doRender) {
            mDecoderOutputSurfaceWrapper.awaitNewImage();
            mDecoderOutputSurfaceWrapper.drawImage();
            mEncoderInputSurfaceWrapper.setPresentationTime(mBufferInfo.presentationTimeUs * 1000);
            mEncoderInputSurfaceWrapper.swapBuffers();
        }
        return DRAIN_STATE_CONSUMED;
    }
    */

    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;
        int result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (mActualOutputFormat != null)
                    throw new RuntimeException("Video output format changed twice.");
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(QueuedMuxer.SampleType.VIDEO, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderOutputBuffers = mEncoder.getOutputBuffers();
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        mMuxer.writeSampleData(QueuedMuxer.SampleType.VIDEO, mEncoderOutputBuffers[result], mBufferInfo);
        mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs;
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }
}
