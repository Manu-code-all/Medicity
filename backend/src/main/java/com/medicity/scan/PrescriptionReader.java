package com.medicity.scan;

import java.util.List;

/**
 * Reads a photographed, handwritten prescription into a draft.
 *
 * <p>Whatever implements this is untrusted: its output is shown to the doctor
 * as a draft to correct, never issued as it is. The model reading the photo
 * can misread a name, invent a dose, or obey text written in the photo; none
 * of that reaches a patient without the doctor checking it line by line.
 */
public interface PrescriptionReader {

    /** Whether this reader can be used at all (an API key is configured). */
    boolean available();

    Reading read(byte[] image, String contentType);

    /** One medicine as the model read it, before it is matched to the catalogue. */
    record ReadLine(String writtenAs, String medicine, String strength, String dosage, String frequency,
                    Integer durationDays, Integer quantity, Double confidence) {}

    record Reading(String diagnosis, List<ReadLine> lines, List<String> unreadable) {}

    /** The model could not be reached, or answered with something that is not a reading. */
    class ReadingFailed extends RuntimeException {
        public ReadingFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
