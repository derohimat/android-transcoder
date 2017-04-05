package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import net.ypresto.androidtranscoder.compat.MediaCodecBufferCompatWrapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/**
 * InputChannel of raw audio from multiple decoders to a single encoder.
 * Performs the necessary conversion between different input & output audio formats.
 *
 * We currently support upmixing from mono to stereo & downmixing from stereo to mono.
 * Sample rate conversion is not supported yet.
 */
class AudioChannel {

    private static class AudioBuffer {
        int bufferIndex;
        Long presentationTimeUs;
        Long startTimeUs;
        Long endTimeUs;
        Long presentationTimeOffsetUs;
        ShortBuffer data;
    }
    private static final String TAG = "AudioChannel";
    public static final int BUFFER_INDEX_END_OF_STREAM = -1;

    private static final int BYTES_PER_SHORT = 2;
    private static final long MICROSECS_PER_SEC = 1000000;

    private final LinkedHashMap<String, Queue<AudioBuffer>> mEmptyBuffers;
    private final LinkedHashMap<String, Queue<AudioBuffer>> mFilledBuffers;

    private final Queue<AudioBuffer> mFilledBuffers2 = new ArrayDeque<>();

    private final LinkedHashMap<String, MediaCodec> mDecoders;
    private final MediaCodec mEncoder;
    private final MediaFormat mEncodeFormat;

    private int mInputSampleRate;
    private int mInputChannelCount;
    private int mOutputChannelCount;

    private AudioRemixer mRemixer;

    private final LinkedHashMap<String, MediaCodecBufferCompatWrapper> mDecoderBuffers;
    private final MediaCodecBufferCompatWrapper mEncoderBuffers;

    private final AudioBuffer mOverflowBuffer = new AudioBuffer();

    private MediaFormat mActualDecodedFormat;
    
    private ShortBuffer mEncoderBuffer;
    private int mEncoderBufferIndex;

    public AudioChannel(final LinkedHashMap<String, MediaCodec> decoders,
                        final MediaCodec encoder, final MediaFormat encodeFormat) {
        mDecoders = decoders;
        mEncoder = encoder;
        mEncodeFormat = encodeFormat;
        mDecoderBuffers = new LinkedHashMap<String, MediaCodecBufferCompatWrapper>();
        mEmptyBuffers = new LinkedHashMap<String, Queue<AudioBuffer>>();
        mFilledBuffers = new LinkedHashMap<String, Queue<AudioBuffer>>();

        for (Map.Entry<String, MediaCodec> entry : mDecoders.entrySet()) {
            MediaCodec decoder = entry.getValue();
            mDecoderBuffers.put(entry.getKey(), new MediaCodecBufferCompatWrapper(decoder));
            Queue<AudioBuffer> empty = new ArrayDeque<>();
            Queue<AudioBuffer> filled = new ArrayDeque<>();
            mEmptyBuffers.put(entry.getKey(), empty);
            mFilledBuffers.put(entry.getKey(), filled);
        }
        mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);
    }

    public AudioChannel createFromExisting(final LinkedHashMap<String, MediaCodec> decoders,
                                           final MediaCodec encoder, final MediaFormat encodeFormat) {

        AudioChannel audioChannel = new AudioChannel(decoders, encoder, encodeFormat);
        for (Map.Entry<String, MediaCodecBufferCompatWrapper> entry : audioChannel.mDecoderBuffers.entrySet()) {
            if (mDecoderBuffers.containsKey(entry.getKey()))
                audioChannel.mDecoderBuffers.put(entry.getKey(), mDecoderBuffers.get(entry.getKey()));
        }
        audioChannel.setActualDecodedFormat(getDeterminedFormat());

        return audioChannel;
    }

    public void setActualDecodedFormat(final MediaFormat decodedFormat) {
        mActualDecodedFormat = decodedFormat;

        mInputSampleRate = mActualDecodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (mInputSampleRate != mEncodeFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)) {
            throw new UnsupportedOperationException("Audio sample rate conversion not supported yet.");
        }

        mInputChannelCount = mActualDecodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        mOutputChannelCount = mEncodeFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        if (mInputChannelCount != 1 && mInputChannelCount != 2) {
            throw new UnsupportedOperationException("Input channel count (" + mInputChannelCount + ") not supported.");
        }

        if (mOutputChannelCount != 1 && mOutputChannelCount != 2) {
            throw new UnsupportedOperationException("Output channel count (" + mOutputChannelCount + ") not supported.");
        }

        if (mInputChannelCount > mOutputChannelCount) {
            mRemixer = AudioRemixer.DOWNMIX;
        } else if (mInputChannelCount < mOutputChannelCount) {
            mRemixer = AudioRemixer.UPMIX;
        } else {
            mRemixer = AudioRemixer.PASSTHROUGH;
        }

        mOverflowBuffer.presentationTimeUs = 0l;
    }

    public MediaFormat getDeterminedFormat() {
        return mActualDecodedFormat;
    }

    public long getBufferDurationUs(String input, final int bufferIndex) {
        // Grab the buffer from the decoder
        MediaCodecBufferCompatWrapper decoderBuffer = mDecoderBuffers.get(input);
        if (mActualDecodedFormat == null) {
            throw new RuntimeException("Buffer received before format!");
        }

        // Get actual decoded data
        final ByteBuffer data =
                bufferIndex == BUFFER_INDEX_END_OF_STREAM ?
                        null : decoderBuffer.getOutputBuffer(bufferIndex);

        return data == null ? 0l : sampleCountToDurationUs(data.limit());

    }

    /**
     * Take the data from a decoder buffer and queue it up to later be fed to the encoder.
     * @param input - channel
     * @param bufferIndex - decoder buffer index
     * @param presentationTimeUs - presentation time for output purposes
     * @param presentationTimeOffsetUs - presentation time offset relative to output
     * @param startTimeUs - start time relative to input (seek)
     * @param endTimeUs - stop time relative to input (duration)
     */
    public void drainDecoderBufferAndQueue(String input, final int bufferIndex,
        final Long presentationTimeUs, Long presentationTimeOffsetUs, Long startTimeUs, Long endTimeUs) {

        // Grab the buffer from the decoder
        MediaCodecBufferCompatWrapper decoderBuffer = mDecoderBuffers.get(input);
        if (mActualDecodedFormat == null) {
            throw new RuntimeException("Buffer received before format!");
        }

        // Get actual decoded data
        final ByteBuffer data =
                bufferIndex == BUFFER_INDEX_END_OF_STREAM ?
                        null : decoderBuffer.getOutputBuffer(bufferIndex);

        // Grab an empty buffer (recycled) or create a new one
        AudioBuffer buffer = mEmptyBuffers.get(input).poll();
        if (buffer == null) {
            buffer = new AudioBuffer();
        }

        // Populate buffer with decoded data
        buffer.bufferIndex = bufferIndex; // Original decoder buffer index
        buffer.presentationTimeUs = presentationTimeUs;
        buffer.presentationTimeOffsetUs = presentationTimeOffsetUs;
        buffer.startTimeUs = startTimeUs;
        buffer.endTimeUs = endTimeUs;
        buffer.data = data == null ? null : data.asShortBuffer();

        // Make sure we have an overflow buffer ready
        if (mOverflowBuffer.data == null) {
            mOverflowBuffer.data = ByteBuffer
                    .allocateDirect(data.capacity())
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
            mOverflowBuffer.data.clear().flip();
        }

        // Add to list of filled buffers to be processed by feedEncoder
        mFilledBuffers.get(input).add(buffer);
    }

    /**
     * Take filled buffers from drainDecoderBufferAndQueue, mix them down if needed and feed them
     * to the encoder, noting that this is not a one-to-one buffer operation and may involved
     * overflowing to an additional buffer (mostly when upmixing)
     * @param timeoutUs
     * @return
     */
    public Long feedEncoder(long timeoutUs) {

        // Determine whether we have an overflow buffer or filled buffer to process
        final boolean hasOverflow = mOverflowBuffer.data != null && mOverflowBuffer.data.hasRemaining();
        boolean isFilled = true;
        for (Map.Entry<String, Queue<AudioBuffer>> entry : mFilledBuffers.entrySet()) {
            if (entry.getValue().isEmpty()) {
                isFilled = false;
            }
        }
        if (!isFilled && !hasOverflow) {
            // No audio data - Bail out
            return null;
        }

        // Get a buffer from the encoder that we can fill
        if (mEncoderBuffer == null) {
           mEncoderBufferIndex = mEncoder.dequeueInputBuffer(timeoutUs);
            if (mEncoderBufferIndex < 0) {
                // Encoder is full - Bail out
                return null;
            }
            mEncoderBuffer = mEncoderBuffers.getInputBuffer(mEncoderBufferIndex).asShortBuffer();
        }

        // Drain overflow first, just hand it over and we will process the rest next time
        if (hasOverflow) {
            long presentationTimeUs = drainOverflow(mEncoderBuffer);
            mEncoder.queueInputBuffer(mEncoderBufferIndex,
                    0, mEncoderBuffer.position() * BYTES_PER_SHORT,
                    presentationTimeUs, 0);
            Log.d(TAG, "Submitting audio overflow buffer at " + presentationTimeUs + " bytes: " + mEncoderBuffer.position() * BYTES_PER_SHORT);
            presentationTimeUs += sampleCountToDurationUs(mEncoderBuffer.position());
            mEncoderBuffer = null;
            return presentationTimeUs;
        }

        // Go through buffers by channel and mix it down into the encoder buffer
        boolean streamPresent = false;
        Long presentationTimeUs  = null;
        for (Map.Entry<String, Queue<AudioBuffer>> entry : mFilledBuffers.entrySet()) {
            Queue<AudioBuffer> filledBuffers = entry.getValue();
            final AudioBuffer decoderBuffer = filledBuffers.poll();
            if (decoderBuffer.bufferIndex != BUFFER_INDEX_END_OF_STREAM) {
                streamPresent = true;
                long bufferPresentationTimeUs = remixAndMaybeFillOverflow(decoderBuffer, mEncoderBuffer);
                if (presentationTimeUs == null || presentationTimeUs.equals(-1l))
                    presentationTimeUs = bufferPresentationTimeUs;
                mDecoders.get(entry.getKey()).releaseOutputBuffer(decoderBuffer.bufferIndex, false);
                mEmptyBuffers.get(entry.getKey()).add(decoderBuffer);
            }
        }
        if (!streamPresent) {
            mEncoder.queueInputBuffer(mEncoderBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            mEncoderBuffer = null;
            return null;
        } else {
            if (mEncoderBuffer.limit() > 0) {
                mEncoder.queueInputBuffer(mEncoderBufferIndex,
                        0, mEncoderBuffer.position() * BYTES_PER_SHORT,
                        presentationTimeUs, 0);
                Log.d(TAG, "Submitting audio buffer at " + presentationTimeUs + " bytes: " + mEncoderBuffer.position() * BYTES_PER_SHORT);
                presentationTimeUs += sampleCountToDurationUs(mEncoderBuffer.position());
                mEncoderBuffer = null;
            } else
                presentationTimeUs = -1l; // ignore it
            return presentationTimeUs;
        }
    }

    public long sampleCountToDurationUs(final int sampleCount) {
        int sampleRate = mInputSampleRate;
        int channelCount = mInputChannelCount;
        return  (MICROSECS_PER_SEC * sampleCount / channelCount + sampleRate - 1) / sampleRate;
    }
    private int durationToSampleCount(final long duration) {
        int sampleRate = mInputSampleRate;
        int channelCount = mInputChannelCount;
        Long samples = ((duration * sampleRate * channelCount) + MICROSECS_PER_SEC - 1)/ MICROSECS_PER_SEC;
        return samples.intValue();
    }

    /**
     * Move the overflow buffer into the encoder buffer
     * @param outBuff
     * @return
     */
    private long drainOverflow(final ShortBuffer outBuff) {
        final ShortBuffer overflowBuff = mOverflowBuffer.data;
        final int overflowLimit = overflowBuff.limit();
        final int overflowSize = overflowBuff.remaining();

        final long beginPresentationTimeUs = mOverflowBuffer.presentationTimeUs +
                sampleCountToDurationUs(overflowBuff.position());

        outBuff.clear();
        // Limit overflowBuff to outBuff's capacity
        overflowBuff.limit(outBuff.capacity());
        // Load overflowBuff onto outBuff
        outBuff.put(overflowBuff);

        if (overflowSize >= outBuff.capacity()) {
            // Overflow fully consumed - Reset
            overflowBuff.clear().limit(0);
        } else {
            // Only partially consumed - Keep position & restore previous limit
            overflowBuff.limit(overflowLimit);
        }

        return beginPresentationTimeUs;
    }

    /**
     * First fill the encoder buffer we have and any remaining samples are put into the
     * overflow buffer which will be sent to the decoder on the next cycle
     * @param input - Decoder buffer
     * @param outBuff - Encoder buffer
     * @return
     */
    private long remixAndMaybeFillOverflow(final AudioBuffer input,
                                           final ShortBuffer outBuff) {
        final ShortBuffer inBuff = input.data;
        final ShortBuffer overflowBuff = mOverflowBuffer.data;

        // Reset position to 0, and set limit to capacity (Since MediaCodec doesn't do that for us)
        inBuff.clear();
        outBuff.clear();

        Log.d(TAG, "remixing buffer at " + (input.presentationTimeUs + input.presentationTimeOffsetUs));

        // Position buffer if needed to skip to start time
        int firstSample  = input.startTimeUs <= input.presentationTimeUs ? 0 :
                durationToSampleCount(input.startTimeUs - input.presentationTimeUs);
        int lastSample = input.endTimeUs == null  ||  input.endTimeUs > input.presentationTimeUs ? inBuff.limit() :
                Math.max(0, durationToSampleCount(input.endTimeUs - input.presentationTimeUs));

        // Nothing to be processed cause we are skipping just return empty buffer
        if (firstSample >= inBuff.limit() || lastSample == 0) {
            outBuff.limit(0);
            return -1; // Buffer won't be sent to encoder because limit is zero
        } else {
            inBuff.position(firstSample);
            inBuff.limit(lastSample);
        }

        if (inBuff.remaining() > outBuff.remaining()) {
            // Overflow
            // Limit inBuff to outBuff's capacity
            inBuff.limit(Math.min(lastSample, outBuff.capacity()));
            mRemixer.remix(inBuff, outBuff);

            // Reset limit to its own capacity & Keep position
            inBuff.limit(Math.min(inBuff.capacity(), lastSample));
            //outBuff.flip();

            // Remix the rest onto overflowBuffer
            // NOTE: We should only reach this point when overflow buffer is empty
            final long consumedDurationUs =
                    sampleCountToDurationUs(inBuff.position());
            mRemixer.remix(inBuff, overflowBuff);

            // Seal off overflowBuff & mark limit
            overflowBuff.flip();
            mOverflowBuffer.presentationTimeUs = input.presentationTimeUs + consumedDurationUs +
            input.presentationTimeOffsetUs;
        } else {
            // No overflow
            mRemixer.remix(inBuff, outBuff);
            //outBuff.flip();
        }

        return Math.max(0, input.presentationTimeUs + input.presentationTimeOffsetUs +
                sampleCountToDurationUs(firstSample));
    }
}
