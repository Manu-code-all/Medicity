package com.medicity.privacy;

/**
 * What a person can agree to. Kept separate, not bundled into one tick box:
 * the DPDP Act asks for consent that is specific to its purpose, and the
 * health-data consent must be something a person can refuse without being
 * told they cannot read the terms.
 */
public enum ConsentType {
    /** The terms of use. */
    TERMS_OF_SERVICE,
    /** Having been given the privacy notice: what is collected, why, for how long, who to ask. */
    PRIVACY_NOTICE,
    /** Explicit agreement to Medicity processing the person's health information to provide the service. */
    HEALTH_DATA_PROCESSING
}
