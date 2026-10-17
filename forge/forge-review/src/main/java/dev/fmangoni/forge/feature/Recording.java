package dev.fmangoni.forge.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * Whether a file is actually a recording, read from its own bytes.
 *
 * <p>The gate used to check that a file of the right name existed and was not empty, which a
 * {@code copy NUL artifacts\feature-x.webm} satisfies. Nothing here can tell whether a clip shows
 * the right program — no decoder ships with the JDK and none is going to be added — but it can
 * refuse the failures that are actually likely: a text file with a video extension, a single
 * frame, a tenth of a second, a thumbnail-sized canvas. Those are what a turn produces when it is
 * trying to satisfy a checker rather than to show someone the program working.
 *
 * <p>The real defence is elsewhere and is not a file check at all: {@code Gate} deletes these
 * before it runs the acceptance commands and requires them to exist afterwards, so a recording has
 * to be produced, from source, by a command Forge itself ran. This class is what stops that command
 * from writing a stub.
 *
 * <p>Hand-rolled parsers, per the standing rule about dependencies. They read headers only and
 * never decode a frame, so the worst a malformed file can do is fail to parse — which is the answer
 * this class exists to give anyway.
 */
@UtilityClass
public class Recording {

    /**
     * Below this it is a still, not a recording.
     *
     * <p>Deliberately lenient. The check that matters is that the clip was produced by a command
     * the gate ran; this floor is only here to catch a stub, and a threshold tight enough to
     * argue about would park working projects over a rounding difference in a frame delay.
     */
    private static final double MIN_SECONDS = 1.0;

    /** Smaller than this shows nothing a person could watch. */
    private static final int MIN_WIDTH = 160;
    private static final int MIN_HEIGHT = 120;

    /** A container with real content in it is not this small, whatever the codec. */
    private static final long MIN_BYTES = 2048;

    public Clip inspect(Path file) {
        byte[] head;
        long size;
        try {
            size = Files.size(file);
            head = read(file);
        } catch (IOException e) {
            return Clip.bad("unreadable", "could not be read: " + e.getMessage());
        }
        Clip clip = switch (format(head)) {
            case "GIF" -> gif(head);
            case "WebM" -> webm(head);
            case "MP4" -> mp4(head);
            default -> Clip.bad("unknown", "is not a video file — the bytes are not a WebM, MP4 "
                    + "or GIF container, whatever the extension says");
        };
        // Last, so a file the format itself can explain is explained by the format. The byte floor
        // is the catch-all for a container that parsed but holds nothing, and "it is 1034 bytes"
        // is a much worse thing to tell the next session than "it has one frame".
        if (clip.usable() && size < MIN_BYTES) {
            return Clip.bad(clip.format(), "is " + size + " bytes, which is a stub rather than a "
                    + "recording");
        }
        return clip;
    }

    /**
     * The whole file, capped.
     *
     * <p>A GIF has to be walked to its end to count frames, and these are a few seconds of screen
     * capture — a couple of megabytes at most. The cap is there so a mistakenly enormous file
     * cannot be read into memory by a gate that runs unattended.
     */
    private byte[] read(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(32 * 1024 * 1024);
        }
    }

    private String format(byte[] head) {
        if (head.length >= 6 && new String(head, 0, 3, StandardCharsets.US_ASCII).equals("GIF")) {
            return "GIF";
        }
        if (head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            return "WebM";
        }
        if (head.length >= 12
                && new String(head, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) {
            return "MP4";
        }
        return "unknown";
    }

    // ----------------------------------------------------------------- GIF

    /**
     * Frames and total delay, walked block by block.
     *
     * <p>The format that most needs checking, because it is the one an agent can write by hand
     * with {@code ImageIO} — and the easiest to write as a single frame of nothing, which is
     * precisely the fake this is looking for.
     */
    private Clip gif(byte[] bytes) {
        int width = le16(bytes, 6);
        int height = le16(bytes, 8);
        int frames = 0;
        int delayHundredths = 0;

        int at = 13;
        // Global colour table, if the packed field says there is one.
        if ((bytes[10] & 0x80) != 0) {
            at += 3 * (1 << ((bytes[10] & 0x07) + 1));
        }

        while (at < bytes.length) {
            int block = bytes[at] & 0xFF;
            if (block == 0x3B) {
                break;
            } else if (block == 0x21) {
                // Extension. 0xF9 is the graphic control block, which carries the frame delay.
                int label = at + 1 < bytes.length ? bytes[at + 1] & 0xFF : 0;
                int sub = at + 2;
                if (label == 0xF9 && sub + 3 < bytes.length) {
                    delayHundredths += le16(bytes, sub + 2);
                }
                at = skipSubBlocks(bytes, sub);
            } else if (block == 0x2C) {
                frames++;
                if (at + 9 >= bytes.length) {
                    break;
                }
                int local = at + 10;
                if ((bytes[at + 9] & 0x80) != 0) {
                    local += 3 * (1 << ((bytes[at + 9] & 0x07) + 1));
                }
                // One byte of LZW minimum code size, then the image's own sub-blocks.
                at = skipSubBlocks(bytes, local + 1);
            } else {
                return Clip.bad("GIF", "is not a readable GIF — it is truncated, or was never "
                        + "finished being written");
            }
        }

        double seconds = delayHundredths / 100.0;
        if (frames < 2) {
            return Clip.bad("GIF", "has " + frames + " frame(s) — a still image with an animated "
                    + "extension is not a recording of anything");
        }
        return verdict(new Clip("GIF", seconds, width, height, frames, ""));
    }

    /** GIF sub-block chains are length-prefixed runs ending at a zero length. */
    private int skipSubBlocks(byte[] bytes, int at) {
        while (at < bytes.length) {
            int length = bytes[at] & 0xFF;
            if (length == 0) {
                return at + 1;
            }
            at += length + 1;
        }
        return bytes.length;
    }

    // ----------------------------------------------------------------- WebM

    /**
     * Clusters, counted by scanning for their element id.
     *
     * <p>Approximate on purpose, and the javadoc says so rather than the code pretending
     * otherwise. Reading a Matroska duration properly means walking variable-width EBML elements
     * through the Segment header, and the answer it would give is only used to reject a stub.
     * A cluster holds a bounded span of frames, so "more than one" separates a real capture from a
     * single-frame file without a parser nobody would maintain.
     */
    private Clip webm(byte[] bytes) {
        int clusters = 0;
        for (int i = 0; i + 3 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0x1F && (bytes[i + 1] & 0xFF) == 0x43
                    && (bytes[i + 2] & 0xFF) == 0xB6 && (bytes[i + 3] & 0xFF) == 0x75) {
                clusters++;
            }
        }
        if (clusters == 0) {
            return Clip.bad("WebM", "is a WebM container with no clusters in it — there is no "
                    + "video data here");
        }
        return new Clip("WebM", 0, 0, 0, 0, "");
    }

    // ----------------------------------------------------------------- MP4

    /** Duration and dimensions from the movie header box, which is plain fixed-width fields. */
    private Clip mp4(byte[] bytes) {
        int moov = findBox(bytes, 0, bytes.length, "moov");
        if (moov < 0) {
            return Clip.bad("MP4", "has no moov box — the file is truncated or is not a movie");
        }
        int end = (int) Math.min(bytes.length, (long) moov + be32(bytes, moov - 8));
        int mvhd = findBox(bytes, moov, end, "mvhd");
        if (mvhd < 0) {
            return Clip.bad("MP4", "has no movie header, so it declares no duration");
        }

        int version = bytes[mvhd] & 0xFF;
        double seconds = 0;
        if (version == 0 && mvhd + 20 <= bytes.length) {
            long timescale = be32(bytes, mvhd + 12);
            long duration = be32(bytes, mvhd + 16);
            seconds = timescale == 0 ? 0 : (double) duration / timescale;
        } else if (version == 1 && mvhd + 32 <= bytes.length) {
            long timescale = be32(bytes, mvhd + 20);
            long duration = be64(bytes, mvhd + 24);
            seconds = timescale == 0 ? 0 : (double) duration / timescale;
        }

        int tkhd = findBox(bytes, moov, end, "tkhd");
        int width = 0;
        int height = 0;
        if (tkhd >= 0) {
            // The last two fields of tkhd are 16.16 fixed-point width and height.
            int box = be32(bytes, tkhd - 8);
            int last = tkhd + box - 8 - 8;
            if (last >= 0 && last + 8 <= bytes.length) {
                width = be32(bytes, last) >>> 16;
                height = be32(bytes, last + 4) >>> 16;
            }
        }
        return verdict(new Clip("MP4", seconds, width, height, 0, ""));
    }

    /** Walks the box tree at one level, returning the offset of the named box's payload. */
    private int findBox(byte[] bytes, int from, int to, String type) {
        int at = from;
        while (at + 8 <= to) {
            int size = be32(bytes, at);
            String name = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
            if (name.equals(type)) {
                return at + 8;
            }
            if (size < 8) {
                return -1;
            }
            // moov holds the boxes being looked for, so descend rather than stepping over it.
            at += name.equals("moov") || name.equals("trak") || name.equals("mdia") ? 8 : size;
        }
        return -1;
    }

    // ----------------------------------------------------------------- shared

    /** The thresholds, applied wherever a format was able to answer the question. */
    private Clip verdict(Clip clip) {
        if (clip.seconds() > 0 && clip.seconds() < MIN_SECONDS) {
            return Clip.bad(clip.format(), "runs for " + String.format("%.2f", clip.seconds())
                    + "s — too short to show anything happening");
        }
        if (clip.width() > 0 && (clip.width() < MIN_WIDTH || clip.height() < MIN_HEIGHT)) {
            return Clip.bad(clip.format(), "is " + clip.width() + "×" + clip.height()
                    + ", which is too small for anyone to see what is happening");
        }
        return clip;
    }

    private int le16(byte[] bytes, int at) {
        return at + 1 < bytes.length ? (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8) : 0;
    }

    private int be32(byte[] bytes, int at) {
        if (at < 0 || at + 4 > bytes.length) {
            return 0;
        }
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private long be64(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < 8 && at + i < bytes.length; i++) {
            value = (value << 8) | (bytes[at + i] & 0xFF);
        }
        return value;
    }
}
