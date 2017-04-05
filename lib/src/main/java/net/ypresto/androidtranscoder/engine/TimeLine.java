package net.ypresto.androidtranscoder.engine;

import java.io.FileDescriptor;
import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the wiring for a time sequence in terms of input channels, output channels and filters
 *
 * TimeLine timeLine = (new TimeLine())
 *      .addChannel("movie1", fd1)
 *      .addChannel("movie2", fd2)
 *      .addChannel("movie3", fd2)
 *      .addChannel("soundtrack", fd3)
 *      .createSegment()
 *          .duration("movie1", 60000)
 *          .audio("soundtrack")
 *          .output("movie1", Filter.SEPIA)
 *      .timeLine().createSegment()
 *          .duration("movie1", 2000)
 *          .seek("movie2", 1000)
 *          .combineAndPipe("movie1", "movie2", Filter.CROSSFADE, "temp")
 *          .audio("soundtrack")
 *          .output("temp", Filter.SEPIA)
 *      .timeLine().createSegment()
 *          .trim(2000)
 *          .audio("soundtrack")
 *          .output("movie2")
 *      .timeLine().createSegment(1000)
 *          .audio("soundtrack")
 *          .combineAndOutput("movie2", "movie3", Filter.CROSSFADE)
 *      .timeLine().createSegment()
 *          .audio("soundtrack")
 *          .output("movie3")
 *      .timeLine().start()
 */
public class TimeLine {

    static long TO_END_OF_FILE = -1;
    private List<Segment> mSegments = new ArrayList<Segment>();
    private LinkedHashMap<String, InputChannel> mChannels = new LinkedHashMap<String, InputChannel>();
    public TimeLine () {}

    public Segment createSegment() {
        for (Segment segment : mSegments)
            segment.isLastSegment = false;
        Segment segment = new Segment(this);
        mSegments.add(segment);
        return segment;
    }

    /**
     * Get a List of all segments
     * @return
     */
    public  List<Segment> getSegments() {
        return mSegments;
    }

    /**
     * Get a List of all channels used for creating the master list of extractors
     * @return
     */
    public  LinkedHashMap<String, InputChannel> getChannels() {return mChannels;}


    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.BOTH));
        return this;
    }

    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addVideoOnlyChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.VIDEO));
        return this;
    }

    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addAudioOnlyChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.AUDIO));
        return this;
    }

    /**
     * Add a video/audio and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @param channelType
     * @return
     */
    public TimeLine addChannel(String inputChannel, FileDescriptor inputFileDescriptor, ChannelType channelType) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, channelType));
        return this;
    }

    /**
     * Get the entire timeline duration
     * @return
     */
    public Long getDuration () {
        long durationUs = 0l;
        for (Segment segment : getSegments()) {
            durationUs += segment.getDuration();
        }
        return durationUs;
    }

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

    public enum Filter {CHROMA_KEY, CROSS_FADE, SEPIA};
    public enum ChannelType {VIDEO, AUDIO, BOTH}

    /**
     * An input file / start time combination
     */
    public class InputChannel {
        public Long mLengthUs;  // Length based on metadata
        public Long mInputStartTimeUs = 0l;
        public Long mInputEndTimeUs;
        public Long mInputAcutalEndTimeUs;
        public Long mInputOffsetUs;
        public ChannelType mChannelType;
        public FileDescriptor mInputFileDescriptor = null;

        InputChannel() {
        }

        InputChannel(FileDescriptor inputFileDescriptor, ChannelType channelType) {
            mInputFileDescriptor = inputFileDescriptor;
            mChannelType = channelType;
        }
    }

    public class Segment {
        private TimeLine mTimeLine;
        private List<VideoPatch> mVideoPatches = new ArrayList<VideoPatch>();
        private HashMap<String, Long> mSeeks = new HashMap<String, Long>();
        private Long mDuration;
        private LinkedHashMap<String, InputChannel> mChannels = new LinkedHashMap<String, InputChannel>();
        public Long mOutputStartTimeUs;
        public boolean isLastSegment = true;

        public Long getDuration () {
            if (mDuration != null)
                return mDuration;
            String firstChannelKey = mChannels.entrySet().iterator().next().getKey();
            return mChannels.get(firstChannelKey).mLengthUs -
                    (mSeeks.get(firstChannelKey) == null ? 0l : mSeeks.get(firstChannelKey));
        }

        public void start (Long segmentStartTimeUs, Segment previousSegment) {

            mOutputStartTimeUs = Math.max(segmentStartTimeUs, previousSegment == null ? 0 :
                previousSegment.mOutputStartTimeUs + previousSegment.getDuration());

            for (HashMap.Entry<String, InputChannel> inputChannelEntry : mChannels.entrySet()) {
                InputChannel inputChannel = inputChannelEntry.getValue();
                String channelName = inputChannelEntry.getKey();
                if (previousSegment != null && previousSegment.mChannels.containsKey(channelName)) {
                    InputChannel previousChannel = previousSegment.mChannels.get(channelName);
                    inputChannel.mInputStartTimeUs = mSeeks.get(channelName) != null ?
                            mSeeks.get(channelName) + previousChannel.mInputAcutalEndTimeUs : previousChannel.mInputAcutalEndTimeUs;
                } else {
                    inputChannel.mInputStartTimeUs = mSeeks.get(channelName) != null ? mSeeks.get(channelName) : 0l;
                }
                inputChannel.mInputOffsetUs = mOutputStartTimeUs - inputChannel.mInputStartTimeUs;
                inputChannel.mInputEndTimeUs = mDuration != null ? inputChannel.mInputStartTimeUs + mDuration : null;
                inputChannel.mInputAcutalEndTimeUs = mOutputStartTimeUs;
            }
        }

        public void forceEndOfStream(long outputPresentationTime) {
            for (HashMap.Entry<String, InputChannel> inputChannelEntry : mChannels.entrySet()) {
                InputChannel inputChannel = inputChannelEntry.getValue();
                inputChannel.mInputEndTimeUs = outputPresentationTime - inputChannel.mInputOffsetUs;
            }
        }

        public TimeLine timeLine () {return mTimeLine;}

        /**
         * Get all channels that participate in this segment
         * @return
         */
        public HashMap<String, InputChannel> getChannels() {
             return mChannels;
        }

        /**
         * Get all video channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getVideoChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.InputChannel> entry : mChannels.entrySet())
                if (entry.getValue().mChannelType == ChannelType.VIDEO || entry.getValue().mChannelType == ChannelType.BOTH)
                    channels.put(entry.getKey(), entry.getValue());
            return channels;
        }

        /**
         * Get all audio channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getAudioChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.InputChannel> entry : mChannels.entrySet())
                if (entry.getValue().mChannelType == ChannelType.AUDIO || entry.getValue().mChannelType == ChannelType.BOTH)
                    channels.put(entry.getKey(), entry.getValue());
            return channels;
        }

        /**
         * Private constructor - use Segment.create() to create a segment
         */
        private Segment(TimeLine timeLine) {
            mTimeLine = timeLine;
        }

        /**
         * Set the duration of the channel for this segment, otherwise to end of stream
         * @param time
         * @return
         */
        public Segment duration(long time) {
            this.mDuration = time * 1000l;
            return this;
        }

        /**
         * Set start time of input channel, otherwise where it left off
         * @param channel
         * @param time
         * @return
         */
        public Segment seek(String channel, long time) {
            this.mSeeks.put(channel, time * 1000l);
            return this;
        }

        /**
         * Add a single channel routed directly to the encoder
         *
         * @param inputChannel
         */
        public Segment audio(String inputChannel) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, null, null));
            mChannels.put(inputChannel, TimeLine.this.mChannels.get(inputChannel));
            return this;
        }


        /**
         * Add a single channel routed directly to the encoder
         *
         * @param inputChannelName
         */
        public Segment output(String inputChannelName) {
            InputChannel inputChannel = TimeLine.this.mChannels.get(inputChannelName);
            if (inputChannel.mChannelType != ChannelType.AUDIO)
                mVideoPatches.add(new VideoPatch(inputChannelName, null, null, null));
            mChannels.put(inputChannelName, inputChannel);
            return this;
        }

        /**
         * Add a single channel input that is filtered before being sent to the encoder
         *
         * @param inputChannel
         */
        public Segment output(String inputChannel, Filter filter) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, null, filter));
            mChannels.put(inputChannel, TimeLine.this.mChannels.get(inputChannel));
            return this;
        }

        /**
         * Add a mapping of input stream(s) to an outputChannel stream, optionally via a filter.
         *
         * @param inputChannel1
         * @param inputChannel2
         * @param filter
         */
        public Segment combineAndOutput(String inputChannel1, String inputChannel2, Filter filter) {
            mVideoPatches.add(new VideoPatch(inputChannel1, inputChannel2, null, filter));
            mChannels.put(inputChannel1, TimeLine.this.mChannels.get(inputChannel1));
            mChannels.put(inputChannel2, TimeLine.this.mChannels.get(inputChannel2));
            return this;
        }

        /**
         * Add a mapping of input stream(s) to an outputChannel stream, optionally via a filter.
         *
         * @param inputChannel1
         * @param inputChannel2
         * @param outputChannel
         * @param filter
         */
        public Segment combineAndPipe(String inputChannel1, String inputChannel2, String outputChannel, Filter filter) {
            mVideoPatches.add(new VideoPatch(inputChannel1, inputChannel2, outputChannel, filter));
            mChannels.put(inputChannel1, TimeLine.this.mChannels.get(inputChannel1));
            mChannels.put(inputChannel2, TimeLine.this.mChannels.get(inputChannel2));
            return this;
        }

        int getChannelCount() {
            return mVideoPatches.size();
        }

        List<VideoPatch> getVideoPatches() {
            return mVideoPatches;
        }

    }
}