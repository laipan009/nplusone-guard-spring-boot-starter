package io.github.laipan009.nplusone.core;

import java.util.UUID;

/**
 * Marker for an implicit load in progress on the current thread. Statements inspected while the marker is on the
 * stack are attributed to this session and subject.
 *
 * @param sessionId Hibernate session identifier
 * @param subject   what is being loaded, for example {@code lazy load of Author (proxy)}
 * @param cacheable whether the mapping declares the loaded entity or collection cacheable
 */
record ImplicitLoad(UUID sessionId, String subject, boolean cacheable) {

    boolean matches(UUID sessionId, String subject) {
        return this.sessionId.equals(sessionId) && this.subject.equals(subject);
    }
}
