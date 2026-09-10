package com.azt.streaming.transcoding.domain;

/**
 * The video encoder this host will actually use, resolved once from what the binary carries.
 *
 * <p>A resolved decision rather than a configured string, so the command builder stays a pure
 * function: it is handed the answer instead of having to shell out for it.
 *
 * @param name the encoder ffmpeg will be given, e.g. {@code libx264}
 * @param preset the rate/quality preset, or null when this encoder has none. Emitting a preset an
 *     encoder does not define is not fatal — ffmpeg warns that the option went unused — but it puts
 *     a line in the log that reads like a defect on every single encode.
 */
public record EncoderChoice(String name, String preset) {

    public static EncoderChoice of(String name) {
        return new EncoderChoice(name, null);
    }

    public boolean hasPreset() {
        return preset != null && !preset.isBlank();
    }
}
