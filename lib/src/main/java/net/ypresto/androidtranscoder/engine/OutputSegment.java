package net.ypresto.androidtranscoder.engine;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Represents the wiring for a time sequence in terms of input channels, output channels and filters
 *
 */
public class OutputSegment {

    /**
     * Represents a mapping of one or two input channels to an output channel, optionally
     * applying a filter.
     */
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

    public enum Filter {CHROMA_KEY, CROSS_FADE, SEPIA}; // Temporary until we implement filters
    public enum Setup {OPEN, USE};
    public enum TearDown {RELEASE, KEEP};

    /**
     * An input file / start time combination
     */
    public class InputChannel {
        public long mInputStartTime;
        public FileDescriptor mInputFileDescriptor = null;
        private TearDown disposition = TearDown.RELEASE;
        InputChannel(){};
        InputChannel(FileDescriptor inputFileDescriptor, long inputStartTime) {
            mInputFileDescriptor = inputFileDescriptor;
            mInputStartTime = inputStartTime;
        }
    }
    private long mDurationUs = 0; // 0 means until end of stream
    private long mOutputStartTime = 0;
    private boolean mIsLast = false;
    private List<VideoPatch> mVideoPatches = new ArrayList<VideoPatch>();
    private HashMap<String, InputChannel> mVideoChannels = new HashMap<String, InputChannel>();
    private OutputSegment mPreviousSegment;

    OutputSegment () {
    }
    OutputSegment (OutputSegment previousSegment) {
        mPreviousSegment = previousSegment;
    }
    static public OutputSegment getInstance () {
        return new OutputSegment();
    }
    public OutputSegment getNextInstance () {
        return new OutputSegment(this);
    }

    public class InputStream {
        public FileDescriptor mInputFileDescriptor;
        public String mChannel;
        public boolean mOpen;
        public boolean mClose;
        InputStream(String channel, FileDescriptor inputFileDescriptor, Boolean open, Boolean close) {
            mChannel = channel;
            mOpen = open;
            mClose = close;
            mInputFileDescriptor = inputFileDescriptor;
        }
    }
    HashMap<String, InputStream> mInputStreams;
    public HashMap<String, InputStream> getVideoInputStreams () {
        return (mInputStreams == null) ? doGetVideoInputFiles() : mInputStreams;
    }
    private HashMap<String, InputStream> doGetVideoInputFiles () {
        return null;
    }
    /**
     * Set the duration of teh segment or else it runs until end of stream on any channel
     * @param durationUs
     * @return
     */
    public OutputSegment duration(long durationUs) {
        mDurationUs = durationUs;
        return this;
    }


    /**
     * Add a video input and assign as a channel
     * @param inputFileDescriptor
     * @param inputChannel
     * @param inputStartTime
     * @return
     */
    public OutputSegment addVideoChannel(String inputChannel, FileDescriptor inputFileDescriptor, String inputFileName, long inputStartTime) {
        mVideoChannels.put(inputChannel, new InputChannel(inputFileDescriptor, inputStartTime));
        return this;
    }

    /**
     * Add a video input and assign as a channel
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public OutputSegment addVideoChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mVideoChannels.put(inputChannel, new InputChannel(inputFileDescriptor, 0l));
        return this;
    }

    /**
     * Add a single channel routed directly to the encoder
     * @param inputChannel
     */
    public OutputSegment mapToEncoder(String inputChannel) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, null, null));
        return this;
    }

    /**
     * Add a single channel input that is filtered before being sent to an outputChannel
     * @param inputChannel
     */
    public OutputSegment mapToChannelWithFilter (String inputChannel, String outputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, outputChannel, filter));
        return this;
    }

    /**
     * Add a single channel input that is filtered before being sent to the encoder
     * @param inputChannel
     */
    public OutputSegment mapToEncoderWithFilter (String inputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel, null, null, filter));
        return this;
    }

    /**
     * Add a mapping of input stream(s) to an outputChannel stream, optionally via a filter.
     * @param inputChannel1
     * @param inputChannel2
      * @param filter
     */
    public OutputSegment mapTwoChannelsToEncoderWithFilter (String inputChannel1,  String inputChannel2,  Filter filter) {
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
    public OutputSegment mapTwoChannelsToChannelWithFilter (String inputChannel1,  String inputChannel2, String outputChannel, Filter filter) {
        mVideoPatches.add(new VideoPatch(inputChannel1, inputChannel2, outputChannel, filter));
        return this;
    }

    int getChannelCount () {
        return mVideoPatches.size();
    }

    HashMap<String, InputChannel> getChannels() {
        return mVideoChannels;
    }

    List <VideoPatch> getVideoPatches () {
        return mVideoPatches;
    }

}
