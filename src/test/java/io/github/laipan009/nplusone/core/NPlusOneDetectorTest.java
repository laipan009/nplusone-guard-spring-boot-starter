package io.github.laipan009.nplusone.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class NPlusOneDetectorTest {

    private static final String BY_ID = "select a.id,a.name from author a where a.id=?";
    private static final String BY_BOOK = "select b.id from book b where b.author_id=?";
    private static final String AUTHOR = "lazy load of Author (proxy)";
    private static final int MAX_REPEATS = 2;

    private final NPlusOneDetector detector = new NPlusOneDetector(MAX_REPEATS, List.of());
    private final UUID session = UUID.randomUUID();

    private void inspectTimes(String sql, int times) {
        for (int i = 0; i < times; i++) {
            detector.inspect(sql);
        }
    }

    private void implicitLoad(UUID sessionId, String subject, String sql) {
        detector.beginImplicitLoad(sessionId, subject);
        detector.inspect(sql);
        detector.endImplicitLoad(sessionId, subject);
    }

    @Nested
    class ImplicitLoads {

        @Test
        void whenSameAssociationLoadedAboveThresholdInOneSession_shouldReportIt() {
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }

            assertThat(detector.drainViolations()).containsExactly(new Violation(
                    Violation.Kind.IMPLICIT_LOAD, "session " + session.toString().substring(0, 8), AUTHOR, BY_ID,
                    MAX_REPEATS + 1));
        }

        @Test
        void whenLoadsSpreadAcrossSessions_shouldNotReport() {
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(UUID.randomUUID(), AUTHOR, BY_ID);
            }

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenLoadsAreForDifferentAssociations_shouldCountSeparately() {
            for (int i = 0; i < MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
                implicitLoad(session, "lazy load of collection Author.books", BY_BOOK);
            }

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenLoadIssuesNoStatement_shouldNotCount() {
            for (int i = 0; i < 10; i++) {
                detector.beginImplicitLoad(session, AUTHOR);
                detector.endImplicitLoad(session, AUTHOR);
            }

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenStatementRunsDuringImplicitLoad_shouldNotCountItAsExplicit() {
            detector.afterTransactionBegin(null);
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).extracting(Violation::kind)
                    .containsExactly(Violation.Kind.IMPLICIT_LOAD);
        }

        @Test
        void whenNestedLoadFailsToEnd_shouldStillEndOuterLoad() {
            detector.beginImplicitLoad(session, AUTHOR);
            detector.beginImplicitLoad(session, "lazy load of collection Author.books");
            detector.endImplicitLoad(session, AUTHOR);
            inspectTimes(BY_ID, MAX_REPEATS + 1);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenEndArrivesWithoutBegin_shouldIgnoreIt() {
            detector.endImplicitLoad(session, AUTHOR);
            inspectTimes(BY_ID, 1);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenSessionContinuesAfterDrain_shouldNotCountItAgain() {
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }
            assertThat(detector.drainViolations()).hasSize(1);

            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenScopeClosesAfterItsSessionWasEvaluated_shouldNotReportItAgain() {
            detector.afterTransactionBegin(null);
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }

            assertThat(detector.drainViolations()).hasSize(1);
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenEvaluatedSessionLoadsInsideNewScope_shouldNotCountItAgain() {
            implicitLoad(session, AUTHOR, BY_ID);
            assertThat(detector.drainViolations()).isEmpty();

            detector.inScope("later work", () -> {
                for (int i = 0; i <= MAX_REPEATS; i++) {
                    implicitLoad(session, AUTHOR, BY_ID);
                }
            });

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenWorkerScopeOutlivesDrain_shouldNotReportOldOrContinuingWork() throws Exception {
            var firstLoadsFinished = new CountDownLatch(1);
            var continueWorker = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var worker = executor.submit(() -> {
                    detector.openScope("request", "background request");
                    detector.afterTransactionBegin(null);
                    try {
                        for (int i = 0; i <= MAX_REPEATS; i++) {
                            implicitLoad(session, AUTHOR, BY_ID);
                            detector.inspect(BY_BOOK);
                        }
                        firstLoadsFinished.countDown();
                        assertThat(continueWorker.await(10, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i <= MAX_REPEATS; i++) {
                            implicitLoad(session, AUTHOR, BY_ID);
                            detector.inspect(BY_BOOK);
                        }
                    } finally {
                        detector.afterTransactionCompletion(null);
                        detector.closeScope("request");
                    }
                    return null;
                });
                try {
                    assertThat(firstLoadsFinished.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(detector.drainViolations()).extracting(Violation::kind)
                            .containsExactly(Violation.Kind.IMPLICIT_LOAD);
                } finally {
                    continueWorker.countDown();
                }
                worker.get(10, TimeUnit.SECONDS);
            }

            assertThat(detector.drainViolations()).isEmpty();
            detector.inScope("next test", () -> inspectTimes(BY_BOOK, MAX_REPEATS + 1));
            assertThat(detector.drainViolations()).extracting(Violation::scope).containsExactly("next test");
        }

        @Test
        void whenLoadsHappenOnAnotherThread_shouldStillReport() throws InterruptedException {
            var worker = new Thread(() -> {
                for (int i = 0; i <= MAX_REPEATS; i++) {
                    implicitLoad(session, AUTHOR, BY_ID);
                }
            });
            worker.start();
            worker.join();

            assertThat(detector.drainViolations()).hasSize(1);
        }
    }

    @Nested
    class ImplicitLoadsAcrossSessions {

        @Test
        void whenEachSessionLoadsOnceInsideOneRequest_shouldReportTheRequest() {
            detector.openScope("request", "HTTP GET /books");
            for (int i = 0; i <= MAX_REPEATS; i++) {
                detector.afterTransactionBegin(null);
                implicitLoad(UUID.randomUUID(), AUTHOR, BY_ID);
                detector.afterTransactionCompletion(null);
            }
            detector.closeScope("request");

            assertThat(detector.drainViolations()).containsExactly(
                    new Violation(Violation.Kind.IMPLICIT_LOAD, "HTTP GET /books", AUTHOR, BY_ID, MAX_REPEATS + 1));
        }

        @Test
        void whenOneSessionAlreadyViolates_shouldNotRepeatItForTheSurroundingScopes() {
            detector.openScope("request", "HTTP GET /books");
            detector.afterTransactionBegin(null);
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, BY_ID);
            }
            detector.afterTransactionCompletion(null);
            detector.closeScope("request");

            assertThat(detector.drainViolations()).extracting(Violation::scope)
                    .containsExactly("session " + session.toString().substring(0, 8));
        }

        @Test
        void whenWorkRunsInScope_shouldReportBothKindsAgainstIt() {
            var result = detector.inScope("nightly export", () -> {
                inspectTimes(BY_BOOK, MAX_REPEATS + 1);
                for (int i = 0; i <= MAX_REPEATS; i++) {
                    implicitLoad(UUID.randomUUID(), AUTHOR, BY_ID);
                }
                return "done";
            });

            assertThat(result).isEqualTo("done");
            assertThat(detector.drainViolations()).extracting(Violation::kind, Violation::scope).containsExactly(
                    tuple(Violation.Kind.IMPLICIT_LOAD, "nightly export"),
                    tuple(Violation.Kind.EXPLICIT_QUERY, "nightly export"));
        }

        @Test
        void whenWorkInScopeThrows_shouldStillCloseTheScope() {
            assertThatThrownBy(() -> detector.inScope("nightly export", (Runnable) () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            inspectTimes(BY_BOOK, MAX_REPEATS + 1);

            assertThat(detector.drainViolations()).isEmpty();
        }

    }

    @Nested
    class ExplicitQueries {

        @Test
        void whenSameSelectRepeatsAboveThresholdInOneTransaction_shouldReportIt() {
            detector.afterTransactionBegin(null);
            inspectTimes(BY_ID, MAX_REPEATS + 1);
            detector.inspect(BY_BOOK);
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).containsExactly(
                    new Violation(Violation.Kind.EXPLICIT_QUERY, "transaction", BY_ID, BY_ID, MAX_REPEATS + 1));
        }

        @Test
        void whenRepeatsSpreadAcrossTransactions_shouldNotReport() {
            for (int i = 0; i <= MAX_REPEATS; i++) {
                detector.afterTransactionBegin(null);
                detector.inspect(BY_ID);
                detector.afterTransactionCompletion(null);
            }

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenSelectsRunOutsideAnyScope_shouldIgnoreThem() {
            inspectTimes(BY_ID, MAX_REPEATS + 1);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenCompletionArrivesWithoutBegin_shouldIgnoreIt() {
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenTransactionRunsInsideRequest_shouldCountInBoth() {
            detector.openScope("request", "HTTP GET /books");
            detector.afterTransactionBegin(null);
            inspectTimes(BY_ID, MAX_REPEATS + 1);
            detector.afterTransactionCompletion(null);
            detector.closeScope("request");

            assertThat(detector.drainViolations()).extracting(Violation::scope, Violation::repeats)
                    .containsExactly(tuple("transaction", 3), tuple("HTTP GET /books", 3));
        }

        @Test
        void whenRepeatsSpreadAcrossTransactionsInOneRequest_shouldReportRequestOnly() {
            detector.openScope("request", "HTTP GET /books");
            for (int i = 0; i <= MAX_REPEATS; i++) {
                detector.afterTransactionBegin(null);
                detector.inspect(BY_ID);
                detector.afterTransactionCompletion(null);
            }
            detector.closeScope("request");

            assertThat(detector.drainViolations()).extracting(Violation::scope).containsExactly("HTTP GET /books");
        }
    }

    @Nested
    class StatementFilter {

        @Test
        void whenNonSelectRepeats_shouldIgnore() {
            detector.afterTransactionBegin(null);
            inspectTimes("update book set title=? where id=?", MAX_REPEATS + 1);
            inspectTimes("delete from book where id=?", MAX_REPEATS + 1);
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenSelectIsUpperCaseOrIndented_shouldStillCount() {
            detector.afterTransactionBegin(null);
            inspectTimes("\n  SELECT a.id FROM author a WHERE a.id=?", MAX_REPEATS + 1);
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/* load author */ ",
                "\n /* first comment */\n/* second comment */ ",
                "-- load author\n",
                "-- load author\r\n /* entity query */ "
        })
        void whenSelectHasLeadingComments_shouldCountBothKindsAndPreserveSql(String comment) {
            var sql = comment + BY_ID;
            detector.inScope("explicit loads", () -> inspectTimes(sql, MAX_REPEATS + 1));
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, sql);
            }

            assertThat(detector.inspect(sql)).isSameAs(sql);
            assertThat(detector.drainViolations()).extracting(Violation::kind, Violation::sql, Violation::repeats)
                    .containsExactlyInAnyOrder(
                            tuple(Violation.Kind.IMPLICIT_LOAD, sql, MAX_REPEATS + 1),
                            tuple(Violation.Kind.EXPLICIT_QUERY, sql, MAX_REPEATS + 1));
        }

        @Test
        void whenCommentsDiffer_shouldKeepOriginalSqlAsExplicitCountingKey() {
            detector.inScope("different SQL text", () -> {
                inspectTimes("/* first query */ " + BY_ID, MAX_REPEATS);
                inspectTimes("/* second query */ " + BY_ID, MAX_REPEATS);
            });

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenAllowlistMatchesLeadingComment_shouldIgnoreBothKinds() {
            var allowing = new NPlusOneDetector(MAX_REPEATS, List.of("approved query"));
            var sql = "/* approved query */ " + BY_ID;
            allowing.inScope("approved loads", () -> {
                for (int i = 0; i <= MAX_REPEATS; i++) {
                    allowing.inspect(sql);
                    allowing.beginImplicitLoad(session, AUTHOR);
                    allowing.inspect(sql);
                    allowing.endImplicitLoad(session, AUTHOR);
                }
            });

            assertThat(allowing.drainViolations()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "select a.id,a.nextval_date from author a where a.id=?",
                "select a.nextval from author a where a.id=?",
                "select nextval_date from author where id=?",
                "select \"nextval\" from author where id=?",
                "select 'nextval' from author where id=?",
                "select a.id from author a where a.name='next value for note_seq'",
                "select a.id /* nextval('note_seq') */ from author a where a.id=?"
        })
        void whenSequenceWordsAppearInOrdinaryQuery_shouldCountBothKinds(String sql) {
            detector.inScope("explicit loads", () -> inspectTimes(sql, MAX_REPEATS + 1));
            for (int i = 0; i <= MAX_REPEATS; i++) {
                implicitLoad(session, AUTHOR, sql);
            }

            assertThat(detector.drainViolations()).extracting(Violation::kind, Violation::sql)
                    .containsExactlyInAnyOrder(
                            tuple(Violation.Kind.IMPLICIT_LOAD, sql), tuple(Violation.Kind.EXPLICIT_QUERY, sql));
        }

        @Test
        void whenSequenceValueIsFetched_shouldIgnoreOnEveryDialect() {
            detector.afterTransactionBegin(null);
            inspectTimes("select next value for note_seq", MAX_REPEATS + 1);
            inspectTimes("select nextval('note_seq')", MAX_REPEATS + 1);
            inspectTimes("select note_seq.nextval from dual", MAX_REPEATS + 1);
            inspectTimes("/* identifier generation */ SELECT next value for note_seq", MAX_REPEATS + 1);
            inspectTimes("-- identifier generation\n select nextval('note_seq')", MAX_REPEATS + 1);
            inspectTimes("/* identifier generation */ select note_seq.nextval from dual", MAX_REPEATS + 1);
            inspectTimes("call next value for note_seq", MAX_REPEATS + 1);
            detector.afterTransactionCompletion(null);

            assertThat(detector.drainViolations()).isEmpty();
        }

        @Test
        void whenSelectMatchesAllowlist_shouldIgnoreItInBothKinds() {
            var allowing = new NPlusOneDetector(MAX_REPEATS, List.of("from author a where"));
            allowing.afterTransactionBegin(null);
            for (int i = 0; i <= MAX_REPEATS; i++) {
                allowing.beginImplicitLoad(session, AUTHOR);
                allowing.inspect(BY_ID);
                allowing.endImplicitLoad(session, AUTHOR);
                allowing.inspect(BY_BOOK);
            }
            allowing.afterTransactionCompletion(null);

            assertThat(allowing.drainViolations()).extracting(Violation::kind, Violation::sql)
                    .containsExactly(tuple(Violation.Kind.EXPLICIT_QUERY, BY_BOOK));
        }

        @Test
        void whenInspecting_shouldReturnSqlUnchanged() {
            assertThat(detector.inspect(BY_ID)).isSameAs(BY_ID);
        }
    }

    @Test
    void whenDrained_shouldForgetViolations() {
        detector.afterTransactionBegin(null);
        inspectTimes(BY_ID, MAX_REPEATS + 1);
        detector.afterTransactionCompletion(null);

        assertThat(detector.drainViolations()).hasSize(1);
        assertThat(detector.drainViolations()).isEmpty();
    }

    @Test
    void whenMaxRepeatsBelowOne_shouldReject() {
        assertThatThrownBy(() -> new NPlusOneDetector(0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
