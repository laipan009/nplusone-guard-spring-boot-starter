package io.github.laipan009.nplusone.autoconfigure;

/**
 * What to do when the application itself ran the same select repeatedly, for example {@code findById} in a loop.
 */
public enum ExplicitQueriesMode {
    /** Log a warning with the statements and a hint; the test passes. */
    LOG,
    /** Fail the test like an implicit N+1. */
    FAIL,
    /** Ignore explicit repeats. */
    OFF
}
