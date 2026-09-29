package com.medicity.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The reader the rest of the application uses: whichever providers have a
 * key, in order. Claude first when both are set, Gemini after it, so a
 * failure of one (a free-tier rate limit, an outage) falls back to the other
 * instead of to typing. With no key at all, the feature is simply off.
 * Changing provider is configuration, not code.
 */
@Component
@Primary
@Slf4j
public class ConfiguredPrescriptionReader implements PrescriptionReader {

    private final List<PrescriptionReader> readers;

    public ConfiguredPrescriptionReader(ClaudePrescriptionReader claude, GeminiPrescriptionReader gemini) {
        this(List.of(claude, gemini));
    }

    ConfiguredPrescriptionReader(List<PrescriptionReader> readers) {
        this.readers = readers;
    }

    @Override
    public boolean available() {
        return readers.stream().anyMatch(PrescriptionReader::available);
    }

    @Override
    public Reading read(byte[] image, String contentType) {
        ReadingFailed last = null;
        for (PrescriptionReader reader : readers) {
            if (!reader.available()) {
                continue;
            }
            try {
                return reader.read(image, contentType);
            } catch (ReadingFailed e) {
                log.warn("{} could not read the photo, trying the next reader: {}",
                        reader.getClass().getSimpleName(), e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new ReadingFailed("No handwriting reader is configured", null);
    }
}
