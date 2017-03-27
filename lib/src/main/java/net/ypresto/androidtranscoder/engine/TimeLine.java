package net.ypresto.androidtranscoder.engine;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;

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

    private List<Segment> mSegments = new ArrayList<Segment>();
    private LinkedHashMap<String, InputChannel> mChannels = new LinkedHashMap<String, InputChannel>();
    public TimeLine () {}

    public Segment createSegment() {
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
        public Long mInputStartTimeUs = 0l;
        public Long mPresentationOutputTime;
        public Long mInputEndTimeUs;
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
        private HashMap<String, Long> mDurations = new HashMap<String, Long>();
        private HashMap<String, InputChannel> mChannels = new HashMap<String, InputChannel>();

        public void start (Long presentationOutputTime) {

            for (HashMap.Entry<String, InputChannel> inputChannelEntry : mChannels.entrySet()) {

                InputChannel inputChannel = inputChannelEntry.getValue();

                inputChannel.mPresentationOutputTime = presentationOutputTime - inputChannel.mInputStartTimeUs;

                // Update start time as long it is greater than current position
                Long startTimeUs = mSeeks.get(inputChannelEntry.getKey());
                if (startTimeUs != null) {
                    if (startTimeUs > inputChannel.mInputEndTimeUs) {
                        inputChannel.mInputStartTimeUs = startTimeUs;
                    }
                }

                // Update duration and set channel duration to overridden duration or leave as null (end of stream)
                Long durationUs = mDurations.get(inputChannelEntry.getKey());
                if (durationUs != null) {
                    inputChannel.mInputEndTimeUs = inputChannel.mInputStartTimeUs + durationUs;
                } else
                    inputChannel.mInputEndTimeUs = null;
            }
        }
        public TimeLine timeLine () {return mTimeLine;}

        /**
         * Get all channels (with updated start/duration) that participate in this segment
         * @return
         */
        public HashMap<String, InputChannel> getChannels() {
             return mChannels;
        }

        /**
         * Private constructor - use Segment.create() to create a segment
         */
        private Segment(TimeLine timeLine) {
            mTimeLine = timeLine;
        }

        /**
         * Set the duration of the channel for this segment, otherwise to end of stream
         * @param channel
         * @param time
         * @return
         */
        public Segment duration(String channel, long time) {
            this.mDurations.put(channel, time);
            return this;
        }

        /**
         * Set start time of input channel, otherwise where it left off
         * @param channel
         * @param time
         * @return
         */
        public Segment seek(String channel, long time) {
            this.mSeeks.put(channel, time);
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
         * @param inputChannel
         */
        public Segment output(String inputChannel) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, null, null));
            mChannels.put(inputChannel, TimeLine.this.mChannels.get(inputChannel));
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

        HashMap<String, InputChannel> getVideoChannels() {
            return TimeLine.this.mChannels;
        }

        List<VideoPatch> getVideoPatches() {
            return mVideoPatches;
        }

    }
}