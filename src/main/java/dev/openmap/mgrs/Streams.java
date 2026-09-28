package dev.openmap.mgrs;

import java.io.IOException;
import java.io.InputStream;

public final class Streams {

    private Streams() {
    }

    // Reads fully up to length; returns short only at end of stream.
    public static int readNBytes(InputStream in, byte[] into, int offset, int length)
            throws IOException {
        if ((offset | length) < 0 || length > into.length - offset) {
            throw new IndexOutOfBoundsException("Range [" + offset + ", " + offset
                    + " + " + length + ") out of bounds for length " + into.length);
        }
        int pos = offset;
        int rem = length;
        while (rem > 0) {
            int read = in.read(into, pos, rem);
            if (read < 0) {
                break;
            }
            pos += read;
            rem -= read;
        }
        return length - rem;
    }
}
