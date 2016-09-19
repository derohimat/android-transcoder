package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import net.ypresto.androidtranscoder.compat.MediaCodecBufferCompatWrapper;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class AudioTrackTranscoder implements TrackTranscoder {

    private static final QueuedMuxer.SampleType SAMPLE_TYPE = QueuedMuxer.SampleType.AUDIO;

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final LinkedHashMap<String, MediaExtractor> mExtractors;
    private final QueuedMuxer mMuxer;
    private long mWrittenPresentationTimeUs;

    private LinkedHashMap<String, Integer> mTrackIndexes;
    private final LinkedHashMap<String, MediaFormat> mInputFormat;
    private final MediaFormat mOutputFormat;

    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private LinkedHashMap<String, MediaCodec> mDecoders;
    private MediaCodec mEncoder;
    private MediaFormat mActualOutputFormat;

    private HashMap<String, MediaCodecBufferCompatWrapper> mDecoderBuffers;
    private MediaCodecBufferCompatWrapper mEncoderBuffers;

    private boolean mIsExtractorEOS;
    private boolean mIsDecoderEOS;
    private boolean mIsEncoderEOS;
    private boolean mDecoderStarted;
    private boolean mEncoderStarted;

    private AudioChannel mAudioChannel;

    public AudioTrackTranscoder(LinkedHashMap<String, MediaExtractor> extractor,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mExtractors = extractor;
        mTrackIndexes = new LinkedHashMap<String, Integer>();
        mOutputFormat = outputFormat;
        mMuxer = muxer;
        mDecoders = new LinkedHashMap<String, MediaCodec>();
        mDecoderBuffers = new HashMap<String, MediaCodecBufferCompatWrapper>();
        mInputFormat = new LinkedHashMap<String, MediaFormat>();
    }

    @Override
    public void setup() {


        for (Map.Entry<String, MediaExtractor> entry : mExtractors.entrySet()) {
            MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(entry.getValue());
            if (trackResult.mAudioTrackFormat != null) {
                MediaExtractor extractor = entry.getValue();
                int trackIndex = trackResult.mVideoTrackIndex;
                mTrackIndexes.put(entry.getKey(), trackIndex);
                entry.getValue().selectTrack(trackIndex);
                MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
                mInputFormat.put(entry.getKey(), inputFormat);
                MediaCodec decoder = null;
                try {
                    decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                decoder.configure(inputFormat, null, null, 0);
                decoder.start();
                mDecoderStarted = true;
                mDecoderBuffers.put(entry.getKey(), new MediaCodecBufferCompatWrapper(decoder));
            }
        }

        try {
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);

        mAudioChannel = new AudioChannel(mDecoders, mEncoder, mOutputFormat);
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mAudioChannel.getDeterminedFormat();
    }

    @Override
    public boolean stepPipeline() {
        boolean busy = false;

        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) busy = true;
        do {
            status = drainDecoder(0);
            if (status != DRAIN_STATE_NONE) busy = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);

        while (mAudioChannel.feedEncoder(0)) busy = true;
        while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true;

        return busy;
    }

    private int drainExtractor(long timeoutUs) {
        if (mIsExtractorEOS) return DRAIN_STATE_NONE;
        for (Map.Entry<String, MediaCodec> entry : mDecoders.entrySet()) {
            MediaExtractor extractor = mExtractors.get(entry.getKey());
            MediaCodec decoder = entry.getValue();
            int trackIndex = extractor.getSampleTrackIndex();
            if (trackIndex >= 0 && trackIndex != mTrackIndexes.get(entry.getKey())) {
                return DRAIN_STATE_NONE;
            }
            int result = decoder.dequeueInputBuffer(timeoutUs);
            if (result < 0) return DRAIN_STATE_NONE;
            if (trackIndex < 0) {
                mIsExtractorEOS = true;
                decoder.queueInputBuffer(result, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return DRAIN_STATE_NONE;
            }
            int sampleSize = extractor.readSampleData(mDecoderBuffers.get(entry.getKey()).getInputBuffer(result), 0);
            boolean isKeyFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            decoder.queueInputBuffer(result, 0, sampleSize, extractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
            extractor.advance();
        }
        return DRAIN_STATE_CONSUMED;
    }

    private int drainDecoder(long timeoutUs) {
          if (mIsDecoderEOS)
            return DRAIN_STATE_NONE;

        int decoderCount = 0;
        int eosCount = 0;
        for (Map.Entry<String, MediaCodec> entry : mDecoders.entrySet()) {
            MediaExtractor extractor = mExtractors.get(entry.getKey());
            MediaCodec decoder = entry.getValue();
            int result = decoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
            switch (result) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    return DRAIN_STATE_NONE;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    mAudioChannel.setActualDecodedFormat(decoder.getOutputFormat());
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            }

            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                eosCount++;
                mAudioChannel.drainDecoderBufferAndQueue(entry.getKey(), AudioChannel.BUFFER_INDEX_END_OF_STREAM, 0);
            } else if (mBufferInfo.size > 0) {
                mAudioChannel.drainDecoderBufferAndQueue(entry.getKey(), result, mBufferInfo.presentationTimeUs);
            }
            decoderCount++;
        }
        if (eosCount == decoderCount) {
            mIsDecoderEOS = true;
        }

        return DRAIN_STATE_CONSUMED;

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
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        mMuxer.writeSampleData(SAMPLE_TYPE, mEncoderBuffers.getOutputBuffer(result), mBufferInfo);
        mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs;
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }

    @Override
    public long getWrittenPresentationTimeUs() {
        return mWrittenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return mIsEncoderEOS;
    }

    @Override
    public void release() {
        if (mDecoders != null) {
            for (Map.Entry<String, MediaCodec> entry : mDecoders.entrySet()) {
                MediaCodec decoder = entry.getValue();
                if (mDecoderStarted) decoder.stop();
                decoder.release();
            }
            mDecoders = null;
        }
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }
}
