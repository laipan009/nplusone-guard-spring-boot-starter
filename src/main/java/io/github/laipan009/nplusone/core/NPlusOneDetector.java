package io.github.laipan009.nplusone.core;

import org.hibernate.Interceptor;
import org.hibernate.Transaction;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Records two kinds of repeated selects.
 *
 * <p><b>Implicit loads</b> are the N+1 proper: Hibernate initializes a proxy, initializes a lazy collection or
 * fetches an EAGER association by a separate select, once per owner row. {@link ImplicitLoadListeners} mark the
 * start and end of every such load on the current thread; statements inspected in between are attributed to the
 * Hibernate session and the association being loaded. When a session has issued more than {@code maxRepeats}
 * statements for the same association, that is a {@link Violation.Kind#IMPLICIT_LOAD} violation. Sessions are
 * evaluated when {@link #drainViolations()} is called, which a test listener does after every test method.
 *
 * <p><b>Scopes</b> are stretches of one thread: a transaction (from the {@link Interceptor} callbacks) or whatever
 * else is opened with {@link #openScope(String, String)}, such as an HTTP request or a call wrapped by a test in
 * {@link #inScope(String, Runnable)}. Implicit loads are counted in every open scope as well, by association and
 * across sessions, so a loop of small transactions that each lazily load one row is caught even though no single
 * session exceeds the threshold.
 *
 * <p><b>Explicit queries</b> are statements the application asked for, counted by text inside the same scopes.
 * The same text more than {@code maxRepeats} times in one scope is a {@link Violation.Kind#EXPLICIT_QUERY}
 * violation. Whether that fails the test is the caller's decision: a loop
 * of {@code findById} is wasteful, but only the author knows whether it is a mistake.
 *
 * <p>Statements that fetch sequence values and statements matching the allowlist are never counted.
 */
public class NPlusOneDetector implements StatementInspector, Interceptor {

    public static final String TRANSACTION_SCOPE = "transaction";
    /** Scope opened by {@link #inScope(String, Runnable)}. */
    public static final String CUSTOM_SCOPE = "custom";

    private static final int EVALUATED_SESSIONS_LIMIT = 10_000;

    private final int maxRepeats;
    private final List<Pattern> allowlist;
    // Interceptor extends Serializable, but a detector holds live per-thread state and is never meant to travel
    private final transient ThreadLocal<Deque<DetectionScope>> openScopes = ThreadLocal.withInitial(ArrayDeque::new);
    private final transient ThreadLocal<Deque<ImplicitLoad>> implicitLoads = ThreadLocal.withInitial(ArrayDeque::new);
    // Counting, scope completion and draining share the detector monitor so each statement belongs to one boundary.
    private final transient Map<UUID, SessionCounts> sessions = new LinkedHashMap<>();
    private final transient Set<UUID> evaluatedSessions = new LinkedHashSet<>();
    private final transient List<Violation> scopeViolations = new ArrayList<>();
    private transient long generation;

    public NPlusOneDetector(int maxRepeats, List<String> allowlist) {
        if (maxRepeats < 1) {
            throw new IllegalArgumentException("maxRepeats must be at least 1, got " + maxRepeats);
        }
        this.maxRepeats = maxRepeats;
        this.allowlist = allowlist.stream().map(Pattern::compile).toList();
    }

    /**
     * Counts the statement and returns it unchanged, as the {@link StatementInspector} contract requires.
     */
    @Override
    public synchronized String inspect(String sql) {
        if (SqlStatement.isCountedSelect(sql) && !isAllowlisted(sql)) {
            var implicitLoad = implicitLoads.get().peek();
            if (implicitLoad == null) {
                openScopes.get().stream().filter(scope -> scope.isCurrent(generation))
                        .forEach(scope -> scope.countExplicit(sql));
            } else if (!evaluatedSessions.contains(implicitLoad.sessionId())) {
                sessions.computeIfAbsent(implicitLoad.sessionId(), SessionCounts::new)
                        .count(implicitLoad.subject(), sql, implicitLoad.cacheable());
                openScopes.get().stream().filter(scope -> scope.isCurrent(generation))
                        .forEach(scope -> scope.countImplicit(
                                implicitLoad.subject(), sql, implicitLoad.cacheable()));
            }
        }
        return sql;
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException(getClass().getName() + " holds live detection state");
    }

    /**
     * Called by {@link ImplicitLoadListeners} before Hibernate performs an implicit load on the current thread.
     */
    public void beginImplicitLoad(UUID sessionId, String subject) {
        beginImplicitLoad(sessionId, subject, false);
    }

    /**
     * @param cacheable whether the mapping declares the loaded entity or collection cacheable; such loads are
     *                  reported as {@link Violation.Kind#CACHEABLE_LOAD} instead of failing the test
     */
    public void beginImplicitLoad(UUID sessionId, String subject, boolean cacheable) {
        implicitLoads.get().push(new ImplicitLoad(sessionId, subject, cacheable));
    }

    /**
     * Called by {@link ImplicitLoadListeners} after Hibernate performed an implicit load. Pops the matching marker
     * together with anything nested above it that a failed load may have left behind.
     */
    public void endImplicitLoad(UUID sessionId, String subject) {
        var stack = implicitLoads.get();
        if (stack.stream().noneMatch(load -> load.matches(sessionId, subject))) {
            return;
        }
        while (!stack.isEmpty()) {
            if (stack.pop().matches(sessionId, subject)) {
                break;
            }
        }
        if (stack.isEmpty()) {
            implicitLoads.remove();
        }
    }

    @Override
    public void afterTransactionBegin(Transaction tx) {
        implicitLoads.remove();
        openScope(TRANSACTION_SCOPE, TRANSACTION_SCOPE);
    }

    @Override
    public void afterTransactionCompletion(Transaction tx) {
        implicitLoads.remove();
        closeScope(TRANSACTION_SCOPE);
    }

    /**
     * Starts counting on the current thread until {@link #closeScope(String)} with the same kind.
     *
     * @param kind        identifies matching open and close calls, for example {@code transaction}
     * @param description appears in the violation message, for example {@code HTTP GET /books}
     */
    public synchronized void openScope(String kind, String description) {
        openScopes.get().push(new DetectionScope(kind, description, generation));
    }

    /**
     * Runs the work inside a scope on the current thread, counting implicit loads and explicit repeats together,
     * and closes the scope whatever happens. For test code that calls a job or listener directly:
     * {@code detector.inScope("nightly export", exportJob::run)}.
     */
    public void inScope(String description, Runnable work) {
        openScope(CUSTOM_SCOPE, description);
        try {
            work.run();
        } finally {
            closeScope(CUSTOM_SCOPE);
        }
    }

    /** Same as {@link #inScope(String, Runnable)} for work that returns a value. */
    public <T> T inScope(String description, Supplier<T> work) {
        openScope(CUSTOM_SCOPE, description);
        try {
            return work.get();
        } finally {
            closeScope(CUSTOM_SCOPE);
        }
    }

    /**
     * Closes the innermost open scope of the given kind and records its violations. Completion callbacks without a
     * matching open scope are ignored: Hibernate can complete a transaction whose begin this detector never saw.
     */
    public synchronized void closeScope(String kind) {
        var scopes = openScopes.get();
        var closed = removeInnermost(scopes, kind);
        if (scopes.isEmpty()) {
            openScopes.remove();
        }
        closed.filter(scope -> scope.isCurrent(generation))
                .ifPresent(scope -> scopeViolations.addAll(scope.violations(maxRepeats)));
    }

    /**
     * Evaluates every Hibernate session seen since the previous call, returns its violations together with the
     * violations recorded by closed scopes, and forgets both. A session that keeps running after this call, for
     * example on a background thread, is not counted again.
     * Scopes still open at this boundary are retired; their later statements and completion are ignored.
     *
     * <p>An association that already violates inside one session is not reported a second time by the transaction
     * or request around that session; scope-level implicit violations are for loads spread across sessions.
     */
    public synchronized List<Violation> drainViolations() {
        var result = new ArrayList<Violation>();
        var reportedSubjects = new java.util.HashSet<String>();
        sessions.forEach((sessionId, counts) -> {
            rememberEvaluated(sessionId);
            for (var violation : counts.violations(maxRepeats)) {
                result.add(violation);
                reportedSubjects.add(violation.subject());
            }
        });
        sessions.clear();
        for (var violation : scopeViolations) {
            if (violation.kind() == Violation.Kind.EXPLICIT_QUERY || reportedSubjects.add(violation.subject())) {
                result.add(violation);
            }
        }
        scopeViolations.clear();
        generation++;
        return result;
    }

    public int maxRepeats() {
        return maxRepeats;
    }

    private void rememberEvaluated(UUID sessionId) {
        if (evaluatedSessions.size() >= EVALUATED_SESSIONS_LIMIT) {
            var oldest = evaluatedSessions.iterator();
            oldest.next();
            oldest.remove();
        }
        evaluatedSessions.add(sessionId);
    }

    private static Optional<DetectionScope> removeInnermost(Deque<DetectionScope> scopes, String kind) {
        var iterator = scopes.iterator();
        while (iterator.hasNext()) {
            var scope = iterator.next();
            if (scope.kind().equals(kind)) {
                iterator.remove();
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }

    private boolean isAllowlisted(String sql) {
        return allowlist.stream().anyMatch(pattern -> pattern.matcher(sql).find());
    }
}
