package com.medicity.common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Helpers for translating database constraint violations into domain errors.
 *
 * <p>The pattern used throughout: attempt the write, let a constraint decide
 * any race, then map the violated constraint's <em>name</em> to a meaningful
 * response. Matching on the name, not the message, keeps an unrelated
 * violation from being misreported as the expected conflict.
 */
public final class Constraints {

    private Constraints() {
    }

    /**
     * The violated constraint's name, or {@code null} if it cannot be found.
     *
     * <p>Spring wraps Hibernate's {@link ConstraintViolationException}, which is
     * where the name actually lives; the Spring-level exception only carries a
     * formatted message.
     */
    public static String nameOf(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }

    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        return constraintName.equalsIgnoreCase(nameOf(e));
    }
}
