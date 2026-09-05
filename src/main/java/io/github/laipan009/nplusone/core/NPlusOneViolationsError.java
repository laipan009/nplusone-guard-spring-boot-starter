package io.github.laipan009.nplusone.core;

import java.util.List;

/**
 * Thrown after a test method when the detector recorded violations that the configuration treats as failures.
 */
public class NPlusOneViolationsError extends AssertionError {

    /** Also rendered into the message, which is what survives serialization by a test runner. */
    private final transient List<Violation> violations;

    public NPlusOneViolationsError(List<Violation> violations, int maxRepeats) {
        super(describe(violations, maxRepeats));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations == null ? List.of() : violations;
    }

    static String describe(List<Violation> violations, int maxRepeats) {
        var implicit = violations.stream().filter(v -> v.kind() == Violation.Kind.IMPLICIT_LOAD).toList();
        var cacheable = violations.stream().filter(v -> v.kind() == Violation.Kind.CACHEABLE_LOAD).toList();
        var explicit = violations.stream().filter(v -> v.kind() == Violation.Kind.EXPLICIT_QUERY).toList();
        var message = new StringBuilder();
        if (!implicit.isEmpty()) {
            message.append(describeImplicit(implicit, maxRepeats));
        }
        if (!cacheable.isEmpty()) {
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append(describeCacheable(cacheable, maxRepeats));
        }
        if (!explicit.isEmpty()) {
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append(describeExplicit(explicit, maxRepeats));
        }
        return message.toString();
    }

    public static String describeCacheable(List<Violation> violations, int maxRepeats) {
        var message = new StringBuilder()
                .append("Cacheable data loaded row by row: the mapping declares it cacheable, so a warm second-level")
                .append(" cache serves it in production, but this session selected it more than ").append(maxRepeats)
                .append(" times\n");
        violations.forEach(violation -> message.append("  ").append(violation).append('\n'));
        return message.append("Enable the second-level cache in the test profile to mirror production, ")
                .append("or fix the association if the cache is not meant to carry this load").toString();
    }

    public static String describeImplicit(List<Violation> violations, int maxRepeats) {
        var message = new StringBuilder()
                .append("N+1 detected: Hibernate loaded the same association by separate selects more than ")
                .append(maxRepeats).append(" times in one session (nplusone.max-repeats=").append(maxRepeats)
                .append(")\n");
        violations.forEach(violation -> message.append("  ").append(violation).append('\n'));
        return message.append("Fix: join fetch, @EntityGraph or @BatchSize on the association").toString();
    }

    public static String describeExplicit(List<Violation> violations, int maxRepeats) {
        var message = new StringBuilder()
                .append("Repeated query: the application ran the same select more than ").append(maxRepeats)
                .append(" times in one transaction or request; one query with in (...) or findAllById would do")
                .append(" (nplusone.explicit-queries)\n");
        violations.forEach(violation -> message.append("  ").append(violation).append('\n'));
        return message.append("Fix the loop, raise nplusone.max-repeats or add the statement to nplusone.allowlist")
                .toString();
    }
}
