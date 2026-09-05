package io.github.laipan009.nplusone.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneViolationsErrorTest {

    private static final String SQL = "select a.id from author a where a.id=?";

    @Test
    void whenImplicitAndExplicit_shouldDescribeBothSectionsWithHints() {
        var exception = new NPlusOneViolationsError(List.of(
                new Violation(Violation.Kind.IMPLICIT_LOAD, "session 1a2b3c4d", "lazy load of Author (proxy)", SQL, 5),
                new Violation(Violation.Kind.EXPLICIT_QUERY, "transaction", SQL, SQL, 4)), 2);

        assertThat(exception).isInstanceOf(AssertionError.class);
        assertThat(exception.getMessage()).isEqualTo("""
                N+1 detected: Hibernate loaded the same association by separate selects more than 2 times in one session (nplusone.max-repeats=2)
                  5 x lazy load of Author (proxy)  [session 1a2b3c4d]
                      select a.id from author a where a.id=?
                Fix: join fetch, @EntityGraph or @BatchSize on the association
                Repeated query: the application ran the same select more than 2 times in one transaction or request; one query with in (...) or findAllById would do (nplusone.explicit-queries)
                  4 x select a.id from author a where a.id=?  [transaction]
                Fix the loop, raise nplusone.max-repeats or add the statement to nplusone.allowlist""");
        assertThat(exception.violations()).hasSize(2);
    }

    @Test
    void whenOnlyImplicit_shouldNotMentionExplicitSection() {
        var exception = new NPlusOneViolationsError(List.of(
                new Violation(Violation.Kind.IMPLICIT_LOAD, "session 1a2b3c4d", "lazy load of Author (proxy)", SQL, 5)), 2);

        assertThat(exception.getMessage()).startsWith("N+1 detected").doesNotContain("Repeated query");
    }
}
