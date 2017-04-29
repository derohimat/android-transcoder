package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import net.ypresto.androidtranscoder.compat.MediaCodecBufferCompatWrapper;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.ypresto.androidtranscoder.engine.AudioChannel.BUFFER_INDEX_END_OF_STREAM;

public class AudioTrackTranscoder implements TrackTranscoder {
    private static final String TAG = "AudioTrackTranscoder";
    private static final QueuedMuxer.SampleType SAMPLE_TYPE = QueuedMuxer.SampleType.AUDIO;

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final LinkedHashMap<String, MediaExtractor> mExtractors;
    private final QueuedMuxer mMuxer;

    private LinkedHashMap<String, Integer> mTrackIndexes;
    private final LinkedHashMap<String, MediaFormat> mInputFormat;
    private final MediaFormat mOutputFormat;

    private MediaCodec mEncoder;
    private MediaFormat mActualOutputFormat;

    private HashMap<String, MediaCodecBufferCompatWrapper> mDecoderBuffers;
    private MediaCodecBufferCompatWrapper mEncoderBuffers;

    private boolean mIsEncoderEOS;
    private boolean mEncoderStarted;

    private AudioChannel mAudioChannel;
    private boolean mIsSegmentFinished;
    private boolean mIsLastSegment = false;
    private long mOutputPresentationTimeDecodedUs = 0l;
    private long mOutputPresentationTimeToSyncToUs = 0l;
    private String mChannelToSyncTo = "";
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public AudioTrackTranscoder(LinkedHashMap<String, MediaExtractor> extractor,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mExtractors = extractor;
        mTrackIndexes = new LinkedHashMap<String, Integer>();
        mOutputFormat = outputFormat;
        mMuxer = muxer;
        mInputFormat = new LinkedHashMap<String, MediaFormat>();
    }
    /**
     * Wraps an extractor -> decoder -> output surface that corresponds to an input channel.
     * The extractor is passed in when created and the start should be called when a segment
     * is found that needs the wrapper.
     */
    private class DecoderWrapper {
        private boolean mIsExtractorEOS;
        private boolean mIsDecoderEOS;
        private boolean mIsSegmentEOS;
        private boolean mDecoderStarted;
        private MediaExtractor mExtractor;
        private MediaCodecBufferCompatWrapper mDecoderInputBuffers;
        private MediaCodec mDecoder;
        private Integer mTrackIndex;
        private long mPresentationTimeDecodedUs = 0;
        boolean mBufferRequeued;
        int mResult;
        private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
        DecoderWrapper(MediaExtractor mediaExtractor) {
            mExtractor = mediaExtractor;
        }

        private void start() {
            MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
            if (trackResult.mAudioTrackFormat != null) {
                int trackIndex = trackResult.mAudioTrackIndex;
                mTrackIndex = trackIndex;
                mExtractor.selectTrack(trackIndex);
                MediaFormat inputFormat = mExtractor.getTrackFormat(trackIndex);

                try {
                    mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                mDecoder.configure(inputFormat, null, null, 0);
                mDecoder.start();
                mDecoderStarted = true;
                mDecoderInputBuffers =  new MediaCodecBufferCompatWrapper(mDecoder);
            }
        }
        private int dequeueOutputBuffer(long timeoutUs) {
            if (!mBufferRequeued)
                mResult = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
            mBufferRequeued = false;
            return mResult;
        }
        private void requeueOutputBuffer() {
            mBufferRequeued = true;
        }
        private void release() {
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        }

    };
    LinkedHashMap<String, DecoderWrapper> mDecoderWrappers = new LinkedHashMap<String, DecoderWrapper>();

    @Override
    public void setupEncoder() {

        try {
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);

    }
    @Override
    public void setupDecoders(TimeLine.Segment segment) {

        Log.d(TAG, "Setting up Audio Decoders for segment at " + segment.mOutputStartTimeUs + " for a duration of " + segment.getDuration());

        // Release any inactive decoders
        Iterator<Map.Entry<String, DecoderWrapper>> iterator = mDecoderWrappers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DecoderWrapper> decoderWrapperEntry = iterator.next();
            if (!segment.getAudioChannels().containsKey(decoderWrapperEntry.getKey())) {
                decoderWrapperEntry.getValue().release();
                iterator.remove();
                Log.d(TAG, "Releasing Audio Decoder " + decoderWrapperEntry.getKey());
            }
        }

        LinkedHashMap<String, MediaCodec> decoders = new LinkedHashMap<String, MediaCodec>();

        // Start any decoders being opened for the first time
        for (Map.Entry<String, TimeLine.InputChannel> entry : segment.getAudioChannels().entrySet()) {
            TimeLine.InputChannel inputChannel = entry.getValue();
            String channelName = entry.getKey();

            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);
            if (decoderWrapper == null) {
                decoderWrapper = new DecoderWrapper(mExtractors.get(channelName));
                mDecoderWrappers.put(channelName, decoderWrapper);
            }
            if (!decoderWrapper.mDecoderStarted) {
                decoderWrapper.start();
            }
            decoderWrapper.mIsSegmentEOS = false;
            Log.d(TAG, "Audio Decoder " + channelName + " at offset " + inputChannel.mInputOffsetUs + " starting at " + inputChannel.mInputStartTimeUs + " ending at " + inputChannel.mInputEndTimeUs);
            decoders.put(entry.getKey(), decoderWrapper.mDecoder);
            mChannelToSyncTo = entry.getKey();
        }

        // Setup an audio channel that will mix from multiple decoders
        mAudioChannel = mAudioChannel == null ? new AudioChannel(decoders, mEncoder, mOutputFormat) :
                        mAudioChannel.createFromExisting(decoders, mEncoder, mOutputFormat);
        mIsSegmentFinished = false;
        mIsEncoderEOS = false;
        mIsLastSegment = segment.isLastSegment;
        Log.d(TAG, "starting an audio segment");
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @Override
    public boolean stepPipeline(TimeLine.Segment outputSegment) {
        boolean stepped = false;
        Long presentationTimeUs;

        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) stepped = true;
        do {
            status = drainDecoder(outputSegment, 0);
            if (status != DRAIN_STATE_NONE) stepped = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);

        while ((presentationTimeUs = mAudioChannel.feedEncoder(0)) != null) {
            if (presentationTimeUs >= 0) {
                Log.d(TAG, "Encoded audio from " + mOutputPresentationTimeDecodedUs + " to " + presentationTimeUs);
                mOutputPresentationTimeDecodedUs = Math.max(mOutputPresentationTimeDecodedUs, presentationTimeUs);
            } else {
                for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
                    decoderWrapperEntry.getValue().mIsSegmentEOS = true;
                }
            }
            stepped = true;
        }
        while (drainExtractors(outputSegment, 0) != DRAIN_STATE_NONE) stepped = true;

        return stepped;
    }

    private int drainExtractors(TimeLine.Segment outputSegment, long timeoutUs) {

        boolean sampleProcessed = false;

        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : outputSegment.getAudioChannels().entrySet()) {

            DecoderWrapper decoderWrapper = mDecoderWrappers.get(inputChannelEntry.getKey());
            if (!decoderWrapper.mIsExtractorEOS) {

                // Find out which track the extractor has samples for next
                int trackIndex = decoderWrapper.mExtractor.getSampleTrackIndex();

                // Sample is for a different track (like audio) ignore
                if (trackIndex >= 0 && trackIndex != decoderWrapper.mTrackIndex) {
                    if (inputChannelEntry.getValue().mChannelType == TimeLine.ChannelType.AUDIO)
                        decoderWrapper.mExtractor.advance(); // Skip video
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
                int sampleSize = decoderWrapper.mExtractor.readSampleData(decoderWrapper.mDecoderInputBuffers.getInputBuffer(result), 0);
                boolean isKeyFrame = (decoderWrapper.mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                decoderWrapper.mDecoder.queueInputBuffer(result, 0, sampleSize, decoderWrapper.mExtractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);


                decoderWrapper.mExtractor.advance();
                sampleProcessed = true;
            }
        }
        return  sampleProcessed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    private int drainDecoder(TimeLine.Segment segment, long timeoutUs) {

        boolean consumed = false;


        // Go through each decoder in the segment and get it's frame into a texture
        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : segment.getAudioChannels().entrySet()) {

            String channelName = inputChannelEntry.getKey();
            TimeLine.InputChannel inputChannel = inputChannelEntry.getValue();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(inputChannelEntry.getKey());

            // Only process if we have not end end of stream for this decoder or extractor
            if (!decoderWrapper.mIsDecoderEOS && !decoderWrapper.mIsSegmentEOS) {

                if (inputChannel.mInputEndTimeUs != null &&
                        mOutputPresentationTimeDecodedUs - inputChannel.mInputOffsetUs > inputChannel.mInputEndTimeUs) {
                    decoderWrapper.mIsSegmentEOS = true;
                    Log.d(TAG, "End of segment audio " + mOutputPresentationTimeDecodedUs + " for decoder " + channelName);
                    continue;
                }

                int result = decoderWrapper.dequeueOutputBuffer(timeoutUs);
                switch (result) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        continue;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        mAudioChannel.setActualDecodedFormat(decoderWrapper.mDecoder.getOutputFormat());
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
                }
                consumed = true;

                // End of stream - requeue the buffer
                if ((decoderWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoderWrapper.mIsDecoderEOS = true;
                    segment.forceEndOfStream(mOutputPresentationTimeDecodedUs);
                    decoderWrapper.requeueOutputBuffer();
                    if (mIsLastSegment)
                        mAudioChannel.drainDecoderBufferAndQueue(channelName, BUFFER_INDEX_END_OF_STREAM, 0l, 0l, 0l, 0l);
                    Log.d(TAG, "Audio End of Stream audio " + mOutputPresentationTimeDecodedUs + " for decoder " + channelName);

                // Process a buffer with data
                } else if (decoderWrapper.mBufferInfo.size > 0) {
                    long endOfBufferTimeUs = decoderWrapper.mBufferInfo.presentationTimeUs +
                         mAudioChannel.getBufferDurationUs(channelName, result);

                    // If we are before start skip entirely
                    if (endOfBufferTimeUs < inputChannel.mInputStartTimeUs) {
                        inputChannel.mInputAcutalEndTimeUs = decoderWrapper.mBufferInfo.presentationTimeUs + mAudioChannel.getBufferDurationUs(channelName, result);
                        if (inputChannel.mInputEndTimeUs != null)
                            inputChannel.mInputAcutalEndTimeUs = Math.min(inputChannel.mInputEndTimeUs, inputChannel.mInputAcutalEndTimeUs);
                        decoderWrapper.mDecoder.releaseOutputBuffer(result, false);
                        Log.d(TAG, "Skipping Audio for Decoder " + channelName + " at " + (decoderWrapper.mBufferInfo.presentationTimeUs + inputChannel.mInputOffsetUs) + " end of buffer " + (endOfBufferTimeUs + inputChannel.mInputOffsetUs));
                        mOutputPresentationTimeToSyncToUs = Math.max(mOutputPresentationTimeDecodedUs + 100000, mOutputPresentationTimeToSyncToUs);

                    // Requeue buffer if to far ahead of other tracks
                    } else if ((decoderWrapper.mBufferInfo.presentationTimeUs + inputChannel.mInputOffsetUs) > mOutputPresentationTimeToSyncToUs) {
                        decoderWrapper.requeueOutputBuffer();
                        Log.d(TAG, "Requeue Audio Buffer because " + decoderWrapper.mBufferInfo.presentationTimeUs + " > " + mOutputPresentationTimeToSyncToUs + " for decoder " + channelName);
                        consumed = false;

                    // Submit buffer for audio mixing
                    } else {
                        Log.d(TAG, "Submitting Audio for Decoder " + channelName + " at " + (decoderWrapper.mBufferInfo.presentationTimeUs + inputChannel.mInputOffsetUs));
                        inputChannel.mInputAcutalEndTimeUs = decoderWrapper.mBufferInfo.presentationTimeUs + mAudioChannel.getBufferDurationUs(channelName, result);
                        if (inputChannel.mInputEndTimeUs != null)
                            inputChannel.mInputAcutalEndTimeUs = Math.min(inputChannel.mInputEndTimeUs, inputChannel.mInputAcutalEndTimeUs);
                        mAudioChannel.drainDecoderBufferAndQueue(channelName, result, decoderWrapper.mBufferInfo.presentationTimeUs, inputChannel.mInputOffsetUs, inputChannel.mInputStartTimeUs, inputChannel.mInputEndTimeUs);
                        mOutputPresentationTimeToSyncToUs = Math.max(mOutputPresentationTimeDecodedUs + 100000, mOutputPresentationTimeToSyncToUs);
                    }
                    decoderWrapper.mPresentationTimeDecodedUs = decoderWrapper.mBufferInfo.presentationTimeUs;
                }
            }
        }
        if (allDecodersEndOfStream()) {
            //if (mIsLastSegment && !mIsSegmentFinished)
            //    mEncoder.signalEndOfInputStream();
            mIsSegmentFinished = true;
        }

        return consumed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    boolean allDecodersEndOfStream () {
        boolean isDecoderEndOfStream = true;
        for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
            if (!(decoderWrapperEntry.getValue().mIsDecoderEOS || decoderWrapperEntry.getValue().mIsSegmentEOS))
                isDecoderEndOfStream = false;
        }
        return isDecoderEndOfStream;
    }

    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;

        int result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (mActualOutputFormat != null) {
                    throw new RuntimeException("Audio output format changed twice.");
                }
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(SAMPLE_TYPE, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mIsSegmentFinished = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        mMuxer.writeSampleData(SAMPLE_TYPE, mEncoderBuffers.getOutputBuffer(result), mBufferInfo);

        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }

    @Override
    public long getOutputPresentationTimeDecodedUs() {
        return mOutputPresentationTimeDecodedUs;
    }

    @Override
    public void setSyncTimeUs(long syncTimeUs) {
        mOutputPresentationTimeToSyncToUs = syncTimeUs;
    }
    @Override
    public long getSyncTimeUs () {
        return mOutputPresentationTimeToSyncToUs;
    }
    @Override
    public String getSyncChannel () {
        return mChannelToSyncTo;
    }
    @Override
    public void setSyncChannel(String syncChannel) {
        mChannelToSyncTo = syncChannel;
    }

    @Override
    public boolean isSegmentFinished() {
        return mIsSegmentFinished;
    }

    @Override
    public void releaseDecoders() {
        for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
            decoderWrapperEntry.getValue().release();
        }
    }
    @Override
    public void releaseEncoder() {
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }
    @Override
    public void release() {
        releaseDecoders();
        releaseEncoder();
    }
}
