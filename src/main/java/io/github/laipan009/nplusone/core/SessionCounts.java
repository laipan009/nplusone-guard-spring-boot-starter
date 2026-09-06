package io.github.laipan009.nplusone.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statements issued by implicit loads inside one Hibernate session, grouped by subject.
 */
final class SessionCounts {

    private final UUID sessionId;
    private final Map<String, Entry> bySubject = new LinkedHashMap<>();

    SessionCounts(UUID sessionId) {
        this.sessionId = sessionId;
    }

    void count(String subject, String sql, boolean cacheable) {
        bySubject.computeIfAbsent(subject, ignored -> new Entry(sql, cacheable)).repeats++;
    }

    List<Violation> violations(int maxRepeats) {
        var scope = "session " + sessionId.toString().substring(0, 8);
        return bySubject.entrySet().stream()
                .filter(entry -> entry.getValue().repeats > maxRepeats)
                .map(entry -> new Violation(
                        entry.getValue().cacheable ? Violation.Kind.CACHEABLE_LOAD : Violation.Kind.IMPLICIT_LOAD,
                        scope, entry.getKey(), entry.getValue().sampleSql, entry.getValue().repeats))
                .toList();
    }

    private static final class Entry {
        private final String sampleSql;
        private final boolean cacheable;
        private int repeats;

        private Entry(String sampleSql, boolean cacheable) {
            this.sampleSql = sampleSql;
            this.cacheable = cacheable;
        }
    }
}
