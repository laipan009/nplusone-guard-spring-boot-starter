package io.github.laipan009.nplusone.core;

/**
 * One repeated load recorded by the detector.
 *
 * @param kind    whether Hibernate issued the statements on its own or the application asked for each of them
 * @param scope   where the repeats happened: {@code session <id>} for implicit loads, {@code transaction} or
 *                {@code HTTP GET /path} for explicit queries
 * @param subject what repeated: the association for implicit loads, the SQL text for explicit queries
 * @param sql     a statement text exactly as Hibernate passed it to JDBC
 * @param repeats how many statements ran inside the scope
 */
public record Violation(Kind kind, String scope, String subject, String sql, int repeats) {

    public enum Kind {
        /**
         * Proxy initialization, lazy collection initialization or an EAGER association fetched by a separate select.
         * The application did not write a loop; Hibernate loaded the association row by row.
         */
        IMPLICIT_LOAD,
        /**
         * An implicit load of an entity or collection that the mapping declares cacheable. A warm second-level
         * cache serves it without statements in production; the statements seen here are a cold-cache cost, not
         * a per-request one, so this is reported rather than failed.
         */
        CACHEABLE_LOAD,
        /**
         * The same query text executed repeatedly, for example {@code findById} in a loop.
         */
        EXPLICIT_QUERY
    }

    @Override
    public String toString() {
        if (kind != Kind.EXPLICIT_QUERY) {
            return repeats + " x " + subject + "  [" + scope + "]\n      " + sql;
        }
        return repeats + " x " + sql + "  [" + scope + "]";
    }
}
