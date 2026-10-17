package dev.fmangoni.forge.feature;






















/**
 * @param seconds  how long it runs, or 0 when the format's duration could not be read
 * @param width    pixels, or 0 when the format's dimensions could not be read
 * @param frames   how many, or 0 when not countable without decoding
 * @param rejected why it is not usable, or blank when it is
 */
public record Clip(String format, double seconds, int width, int height, int frames,
                   String rejected) {

    public boolean usable() {
        return rejected.isBlank();
    }

    static Clip bad(String format, String why) {
        return new Clip(format, 0, 0, 0, 0, why);
    }

    /** What the report says under a clip, so a reader knows what they are about to watch. */
    public String describe() {
        StringBuilder out = new StringBuilder(format);
        if (seconds > 0) {
            out.append(" · ").append(Math.round(seconds)).append("s");
        }
        if (width > 0) {
            out.append(" · ").append(width).append("×").append(height);
        }
        return out.toString();
    }
}
