package net.ypresto.androidtranscoder.engine;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Represents the wiring for a time sequence in terms of input channels, output channels and filters
 *
 * OutputSegments.getInstance()
 *      .addChannel("movie1", fd1)
 *      .addChannel("movie2", fd2)
 *      .addChannel("movie3", fd2)
 *
 * OutputSegments.getInstance()
 *      .createSegment()
 *          .duraction("movie1", 60000)
 *          .output("movie1", Filter.SEPIA)
 *
 * OutputSegments.getInstance()
 *      .createSegment()
 *          .duration("movie1", 2000)
 *          .seek("movie2", 1000)
 *          .combineAndPipe("movie1", "movie2", Filter.CROSSFADE, "temp")
 *          .filter("temp", Filter.SEPIA)
 *
 * OutputSegments.getInstance()
 *      .createSegment()
 *          .trim(2000)
 *          .output("movie2")
 *
 * OutputSegments.getInstance()
 *      .createSegment(1000)
 *          .combineAndOutput("movie2", "movie3", Filter.CROSSFADE)
 *
 * OutputSegments.getInstance()
 *      .createSegment()
 *          .output("movie3")
 *
 * OuputSegments.getInstance()
 *      .start()
 */
public class OutputSegments {

    private static OutputSegments instance = null;
    private List<Segment> mSegments = new ArrayList<Segment>();
    private HashMap<String, InputChannel> mChannels = new HashMap<String, InputChannel>();

    protected OutputSegments() {}

    public static OutputSegments getInstance() {
        if(instance == null) {
            instance = new OutputSegments();
        }
        return instance;

    }
    public Segment createSegment() {
        Segment segment = new Segment();
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
    public  HashMap<String, InputChannel> getChannels() {return mChannels;}


    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public OutputSegments addChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.BOTH));
        return this;
    }

    /**
     * Add a video/auodio and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @param channelType
     * @return
     */
    public OutputSegments addChannel(String inputChannel, FileDescriptor inputFileDescriptor, ChannelType channelType) {
        mChannels.put(inputChannel, new InputChannel(inputFileDescriptor, channelType));
        return this;
    }

    public void start () {
        Segment previousSegment = null;
        for (Segment segment : mSegments) {
            // Add any channels not present in the previous segment to the new channel list
            for (HashMap.Entry<String, InputChannel> inputChannelEntry : segment.mActiveChannels.entrySet()) {
                if (previousSegment == null || previousSegment.mActiveChannels.get(inputChannelEntry.getKey()) == null) {
                    segment.mNewChannels.put(inputChannelEntry.getKey(), inputChannelEntry.getValue());
                }
            }
            // Add any channels in the previous segment not in current segment to previous segment final list
            if (previousSegment != null) {
                for (HashMap.Entry<String, InputChannel> inputChannelEntryPrevious : previousSegment.mActiveChannels.entrySet()) {
                    if (segment.mActiveChannels.get(inputChannelEntryPrevious.getKey()) == null) {
                        segment.mFinalChannels.put(inputChannelEntryPrevious.getKey(), inputChannelEntryPrevious.getValue());
                    }
                }
            }
            previousSegment = segment;
        }
        // All of the channels in the last segment are final
        if (previousSegment != null) {
            for (HashMap.Entry<String, InputChannel> inputChannelEntryLast : previousSegment.mActiveChannels.entrySet()) {
                previousSegment.mFinalChannels.put(inputChannelEntryLast.getKey(), inputChannelEntryLast.getValue());
            }
        }

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
        public Long mInputStartTimeUs;
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

        private List<VideoPatch> mVideoPatches = new ArrayList<VideoPatch>();
        private HashMap<String, Long> mSeeks = new HashMap<String, Long>();
        private HashMap<String, Long> mDurations = new HashMap<String, Long>();
        private HashMap<String, InputChannel> mNewChannels = new HashMap<String, InputChannel>();
        private HashMap<String, InputChannel> mFinalChannels = new HashMap<String, InputChannel>();
        private HashMap<String, InputChannel> mActiveChannels = new HashMap<String, InputChannel>();

        /**
         * Get all channels for which extractors should be attached at start of segment
         * @return
         */
        public HashMap<String, InputChannel> getNewChannels() { return mNewChannels; }

        /**
         * Get all channels for which extractors should be released at end of segment
         * @return
         */
        public HashMap<String, InputChannel> getFinalChannels() { return mFinalChannels; }

        /**
         * Get all channels (with updated start/duration) that participate in this segment
         * @return
         */
        public HashMap<String, InputChannel> getActiveChannels() {

            // Update start time and durations
            for (HashMap.Entry<String, InputChannel> inputChannelEntry : mActiveChannels.entrySet()) {
                InputChannel inputChannel = inputChannelEntry.getValue();

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
            return mActiveChannels;
        }

        /**
         * Private constructor - use Segment.create() to create a segment
         */
        private Segment() {}

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
        public Segment output(String inputChannel) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, null, null));
            mActiveChannels.put(inputChannel, mChannels.get(inputChannel));
            return this;
        }

        /**
         * Add a single channel input that is filtered before being sent to an outputChannel
         *
         * @param inputChannel
         */
        public Segment pipe(String inputChannel, String outputChannel, Filter filter) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, outputChannel, filter));
            mActiveChannels.put(inputChannel, mChannels.get(inputChannel));
            return this;
        }

        /**
         * Add a single channel input that is filtered before being sent to the encoder
         *
         * @param inputChannel
         */
        public Segment output(String inputChannel, Filter filter) {
            mVideoPatches.add(new VideoPatch(inputChannel, null, null, filter));
            mActiveChannels.put(inputChannel, mChannels.get(inputChannel));
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
            mActiveChannels.put(inputChannel1, mChannels.get(inputChannel1));
            mActiveChannels.put(inputChannel2, mChannels.get(inputChannel2));
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
            mActiveChannels.put(inputChannel1, mChannels.get(inputChannel1));
            mActiveChannels.put(inputChannel2, mChannels.get(inputChannel2));
            return this;
        }

        int getChannelCount() {
            return mVideoPatches.size();
        }

        HashMap<String, InputChannel> getVideoChannels() {
            return mChannels;
        }

        /**
         * Get a list of channels to open for this segment
         *
         * @return
         */
        HashMap<String, InputChannel> getVideoChannelsToOpen() {
            return mChannels;
        }

        HashMap<String, InputChannel> getVideoChannelsToClose() {
            return mChannels;
        }


        List<VideoPatch> getVideoPatches() {
            return mVideoPatches;
        }

    }
}