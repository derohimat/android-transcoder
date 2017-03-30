package net.ypresto.androidtranscoder.engine;

import java.nio.ShortBuffer;

public class AudioRemixer {

    void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {};

    static final int SIGNED_SHORT_LIMIT = 32768;
    static final int UNSIGNED_SHORT_MAX = 65535;

    // Viktor Toth's algorithm -
    // See: http://www.vttoth.com/CMS/index.php/technical-notes/68
    //      http://stackoverflow.com/a/25102339
    static private short mix (int a, int b) {
        a = a + SIGNED_SHORT_LIMIT;
        b = b + SIGNED_SHORT_LIMIT;
        int m;
        // Pick the equation
        if ((a < SIGNED_SHORT_LIMIT) || (b < SIGNED_SHORT_LIMIT)) {
            // Viktor's first equation when both sources are "quiet"
            // (i.e. less than middle of the dynamic range)
            m = a * b / SIGNED_SHORT_LIMIT;
        } else {
            // Viktor's second equation when one or both sources are loud
            m = 2 * (a + b) - (a * b) / SIGNED_SHORT_LIMIT - UNSIGNED_SHORT_MAX;
        }
        // Convert output back to signed short
        if (m == UNSIGNED_SHORT_MAX + 1) m = UNSIGNED_SHORT_MAX;
        return (short) (m - SIGNED_SHORT_LIMIT);

    }

    static AudioRemixer DOWNMIX = new AudioRemixer() {

        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {
            // Down-mix stereo to mono
            final int inRemaining = inSBuff.remaining() / 2;
            final int outSpace = outSBuff.remaining();
            final int samplesToBeProcessed = Math.min(inRemaining, outSpace);

            if (outSBuff.position() > 0) {
                ShortBuffer outSBuffCopy = outSBuff.asReadOnlyBuffer();
                outSBuffCopy.rewind();
                for (int i = 0; i < samplesToBeProcessed; ++i) {
                    // Convert to unsigned
                    final int aLeft = inSBuff.get();
                    final int aRight = inSBuff.get();
                    final int bLeft = outSBuffCopy.get();
                    final int bRight = outSBuffCopy.get();
                    outSBuff.put(mix(mix(aLeft, bLeft), mix(aRight, bRight)));
                }
            } else {

                for (int i = 0; i < samplesToBeProcessed; ++i) {
                    // Convert to unsigned
                    final int a = inSBuff.get();
                    final int b = inSBuff.get();
                    outSBuff.put(mix(a, b));
                }
            }
        }
    };

    static AudioRemixer UPMIX = new AudioRemixer() {
        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {
            // Up-mix mono to stereo
            final int inRemaining = inSBuff.remaining();
            final int outSpace = outSBuff.remaining() / 2;

            final int samplesToBeProcessed = Math.min(inRemaining, outSpace);
            if (outSBuff.position() > 0) {
                ShortBuffer outSBuffCopy = outSBuff.asReadOnlyBuffer();
                outSBuffCopy.rewind();
                for (int i = 0; i < samplesToBeProcessed; ++i) {
                    // Convert to unsigned
                    final int a = inSBuff.get();
                    final int b = outSBuffCopy.get();
                    short m = mix(a, b);
                    outSBuff.put(m);
                    outSBuff.put(m);
                }
            } else {
                for (int i = 0; i < samplesToBeProcessed; ++i) {
                    final short inSample = inSBuff.get();
                    outSBuff.put(inSample);
                    outSBuff.put(inSample);
                }
            }
        }
    };

    static AudioRemixer PASSTHROUGH = new AudioRemixer() {
        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {

            if (outSBuff.position() > 0) {
                ShortBuffer outSBuffCopy = outSBuff.asReadOnlyBuffer();
                outSBuffCopy.rewind();
                final int inRemaining = inSBuff.remaining();
                final int outSpace = outSBuff.remaining();

                final int samplesToBeProcessed = Math.min(inRemaining, outSpace);
                for (int i = 0; i < samplesToBeProcessed; ++i) {
                    // Convert to unsigned
                    final int aLeft = inSBuff.get();
                    final int aRight = inSBuff.get();
                    final int bLeft = outSBuffCopy.get();
                    final int bRight = outSBuffCopy.get();
                    outSBuff.put(mix(aLeft, bLeft));
                    outSBuff.put(mix(aRight, bRight));
                }

            } else {

                // Passthrough
                outSBuff.put(inSBuff);
            }
        }
    };

}
