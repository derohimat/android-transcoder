package net.ypresto.androidtranscoder.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Represents the wiring for a time sequence in terms of input channels, output channels and filters
 *
 */
public class OutputSegment {

     public class VideoPatch {
        public String mInput1;
        public String mInput2;
        public String mOutput;
        public Filter mFilter;
        VideoPatch(String input1, String input2, String output, Filter filter) {
            mInput1 = input1;
            mInput2 = input2;
            mOutput = output;
            mFilter = filter;
        }
    }
    enum Filter {CHROMA_KEY, CROSS_FADE, SEPIA}; // Temporary until we implement filters
    public class VideoChannel {
        public long mInputStartTime;
        VideoChannel (long inputStartTime) {
            mInputStartTime = inputStartTime;
        }
    }
    private long mDurationUs = 0; // 0 means until end of stream
    private boolean mIsLast = false;
    private List<VideoPatch> mVideoPatches = new ArrayList<VideoPatch>();
    private HashMap<String, VideoChannel> mChannels = new HashMap<String, VideoChannel>();

    OutputSegment () {
    }
    static OutputSegment create () {
        return new OutputSegment();
    }

    /**
     * Set the duration of teh segment or else it runs until end of stream on any channel
     * @param durationUs
     * @return
     */
    OutputSegment duration(long durationUs) {
        mDurationUs = durationUs;
        return this;
    }

    /**
     * Add a video input and assign as a channel
     * @param inputChannel
     * @param inputStartTime
     */
    OutputSegment addChannel (String inputChannel, long inputStartTime) {
        mChannels.put(inputChannel, new VideoChannel(inputStartTime));
        return this;
    }

    /**
     * Add a video input and assign as a channel
     * @param inputChannel
     */
    OutputSegment addChannel (String inputChannel) {
        mChannels.put(inputChannel, 0l);
        return this;
    }

    /**
     * Add a single channel routed directly to the encoder
     * @param inputChannel
     */
    OutputSegment addMap (String inputChannel) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, null, null));
        return this;
    }

    /**
     * Add a single channel input that is filtered before being sent to an outputChannel
     * @param inputChannel
     */
    OutputSegment mapToChannelWithFilter (String inputChannel, String outputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, outputChannel, filter));
        return this;
    }

    /**
     * Add a single channel input that is filtered before being sent to the encoder
     * @param inputChannel
     */
    OutputSegment mapToEncoderWithFilter (String inputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, null, filter));
        return this;
    }

    /**
     * Add a mapping of input stream(s) to an outputChannel stream, optionally via a filter.
     * @param inputChannel1
     * @param inputChannel2
      * @param filter
     */
    OutputSegment mapTwoChannelsToEncoderWithFilter (String inputChannel1,  String inputChannel2,  Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel1, inputChannel2, null, filter));
        return this;
    }

    /**
     * Add a mapping of input stream(s) to an outputChannel stream, optionally via a filter. 
     * @param inputChannel1
     * @param inputChannel2
     * @param outputChannel
     * @param filter
     */
    OutputSegment mapTwoChannelsToChannelWithFilter (String inputChannel1,  String inputChannel2, String outputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel1, inputChannel2, outputChannel, filter));
        return this;
    }

    int getChannelCount () {
        return mVideoPatches.size();
    }

    HashMap<String, VideoChannel> getChannels() {
        return mChannels;
    }

    List <VideoPatch> getVideoPatches () {
        return mVideoPatches;
    }

}
