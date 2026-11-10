package com.github.miguelaguiar01.spotless.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed frames: {@code uint32 big-endian length} followed by that many bytes of UTF-8
 * JSON. One frame carries exactly one message.
 */
public final class FrameCodec {
    /** Hard upper bound; a source file is never anywhere near this. */
    public static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;

    private FrameCodec() {}

    public static final class Reader {
        private final DataInputStream in;

        public Reader(InputStream in) {
            this.in = new DataInputStream(in);
        }

        /** Returns the next frame as a string, or {@code null} on a clean end of stream. */
        public String readFrame() throws IOException {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException eof) {
                return null;
            }
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("Invalid frame length " + length);
            }
            byte[] buf = new byte[length];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    public static final class Writer {
        private final DataOutputStream out;

        public Writer(OutputStream out) {
            this.out = new DataOutputStream(out);
        }

        public synchronized void writeFrame(String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FRAME_BYTES) {
                throw new IOException("Frame too large: " + bytes.length);
            }
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
        }
    }
}
