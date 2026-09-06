package io.github.laipan009.nplusone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts statements between two points on one thread: a transaction, an HTTP request, or anything else that
 * {@link NPlusOneDetector#openScope(String, String)} is called for.
 *
 * <p>Implicit loads are counted by association across every session the scope spans, which is what catches a loop
 * of small transactions that each lazily load one row. Explicit statements are counted by text.
 */
final class DetectionScope {

    private final String kind;
    private final String description;
    private final long generation;
    private final Map<String, Integer> explicitBySql = new LinkedHashMap<>();
    private final Map<String, ImplicitEntry> implicitBySubject = new LinkedHashMap<>();

    DetectionScope(String kind, String description, long generation) {
        this.kind = kind;
        this.description = description;
        this.generation = generation;
    }

    String kind() {
        return kind;
    }

    boolean isCurrent(long currentGeneration) {
        return generation == currentGeneration;
    }

    void countExplicit(String sql) {
        explicitBySql.merge(sql, 1, Integer::sum);
    }

    void countImplicit(String subject, String sql, boolean cacheable) {
        implicitBySubject.computeIfAbsent(subject, ignored -> new ImplicitEntry(sql, cacheable)).repeats++;
    }

    List<Violation> violations(int maxRepeats) {
        var result = new ArrayList<Violation>();
        implicitBySubject.forEach((subject, entry) -> {
            if (entry.repeats > maxRepeats) {
                result.add(new Violation(
                        entry.cacheable ? Violation.Kind.CACHEABLE_LOAD : Violation.Kind.IMPLICIT_LOAD,
                        description, subject, entry.sampleSql, entry.repeats));
            }
        });
        explicitBySql.forEach((sql, repeats) -> {
            if (repeats > maxRepeats) {
                result.add(new Violation(Violation.Kind.EXPLICIT_QUERY, description, sql, sql, repeats));
            }
        });
        return result;
    }

    private static final class ImplicitEntry {
        private final String sampleSql;
        private final boolean cacheable;
        private int repeats;

        private ImplicitEntry(String sampleSql, boolean cacheable) {
            this.sampleSql = sampleSql;
            this.cacheable = cacheable;
        }
    }
}
