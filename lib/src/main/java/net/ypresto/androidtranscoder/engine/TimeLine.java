package net.ypresto.androidtranscoder.engine;

import java.io.FileDescriptor;
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
 *          .duration(60000)
 *          .audio("soundtrack")
 *          .output("movie1", Filter.SEPIA)
 *      .timeLine().createSegment()
 *          .duration(2000)
 *          .seek("movie2", 1000)
 *          .combineAndPipe("movie1", "movie2", Filter.CROSSFADE, "temp")
 *          .audio("soundtrack")
 *          .output("temp", Filter.SEPIA)
 *      .timeLine().createSegment()
*           .duration("4000")
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
    private LinkedHashMap<String, InputChannel> mTimeLineChannels = new LinkedHashMap<String, InputChannel>();
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
    public  LinkedHashMap<String, InputChannel> getChannels() {return mTimeLineChannels;}


    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.AUDIO_VIDEO));
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
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.VIDEO));
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
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.AUDIO));
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
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, channelType));
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
    public class SegmentChannel {
        public InputChannel mChannel;
        public Filter mFilter;

        SegmentChannel(InputChannel input, Filter filter) {
            mChannel = input;
            mFilter = filter;
        }
    }

    public enum Filter {OPACITY_UP_RAMP, OPACITY_DOWN_RAMP};
    public enum ChannelType {VIDEO, AUDIO, AUDIO_VIDEO, IMAGE}

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
        private LinkedHashMap<String, SegmentChannel> mSegmentChannels = new LinkedHashMap();
        private HashMap<String, Long> mSeeks = new HashMap<String, Long>();
        private Long mDuration;
        public Long mOutputStartTimeUs;
        public boolean isLastSegment = true;

        public Long getDuration () {
            if (mDuration != null)
                return mDuration;

            HashMap.Entry<String, SegmentChannel> firstChannelEntry = mSegmentChannels.entrySet().iterator().next();
            return firstChannelEntry.getValue().mChannel.mLengthUs -
                    (mSeeks.get(firstChannelEntry.getKey()) == null ? 0l : mSeeks.get(firstChannelEntry.getKey()));
        }

        public void start (Long segmentStartTimeUs, Segment previousSegment) {

            mOutputStartTimeUs = Math.max(segmentStartTimeUs, previousSegment == null ? 0 :
                previousSegment.mOutputStartTimeUs + previousSegment.getDuration());

            for (HashMap.Entry<String, SegmentChannel> segmentChannelEntry : mSegmentChannels.entrySet()) {
                SegmentChannel segmentChannel = segmentChannelEntry.getValue();
                String channelName = segmentChannelEntry.getKey();
                InputChannel inputChannel = segmentChannel.mChannel;
                if (previousSegment != null && previousSegment.mSegmentChannels.containsKey(channelName)) {
                    InputChannel previousChannel = mTimeLine.mTimeLineChannels.get(channelName);
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
            for (HashMap.Entry<String, SegmentChannel> segmentChannelEntry : mSegmentChannels.entrySet()) {
                InputChannel inputChannel = segmentChannelEntry.getValue().mChannel;
                inputChannel.mInputEndTimeUs = outputPresentationTime - inputChannel.mInputOffsetUs;
            }
        }

        public TimeLine timeLine () {return mTimeLine;}

        /**
         * Get all channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all video channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getImageChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.IMAGE)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all video channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getVideoChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.VIDEO || entry.getValue().mChannel.mChannelType == ChannelType.AUDIO_VIDEO)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all audio channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getAudioChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.AUDIO || entry.getValue().mChannel.mChannelType == ChannelType.AUDIO_VIDEO)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
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
         * @param inputChannelName
         */
        public Segment output(String inputChannelName) {
            InputChannel inputChannel = mTimeLineChannels.get(inputChannelName);
            if (inputChannel.mChannelType != ChannelType.AUDIO)
                mSegmentChannels.put(inputChannelName, new SegmentChannel(inputChannel, null));
            return this;
        }

        /**
         * Add a single channel input that is filtered before being sent to the encoder
         *
         * @param inputChannelName
         * @param filter
         */
        public Segment output(String inputChannelName, Filter filter) {
            InputChannel inputChannel = mTimeLineChannels.get(inputChannelName);
            mSegmentChannels.put(inputChannelName, new SegmentChannel(inputChannel, filter));
            return this;
        }


        int getChannelCount() {
            return mSegmentChannels.size();
        }

        LinkedHashMap<String, SegmentChannel> getSegmentChannels() {
            return mSegmentChannels;
        }

    }
}