package dev.lm15.types;

/** A live-session audio format. */
public record AudioFormat(AudioEncoding encoding, int sampleRate, int channels) {
    public AudioFormat {
        Check.required(encoding, "audio encoding");
        if (sampleRate <= 0) throw ValidationException.value("sample_rate must be > 0");
        if (channels <= 0) throw ValidationException.value("channels must be > 0");
    }

    public AudioFormat(AudioEncoding encoding, int sampleRate) { this(encoding, sampleRate, 1); }
}
