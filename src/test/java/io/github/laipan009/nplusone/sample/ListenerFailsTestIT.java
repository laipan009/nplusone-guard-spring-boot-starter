package io.github.laipan009.nplusone.sample;

import io.github.laipan009.nplusone.core.NPlusOneViolationsError;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedSuccessfully;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

/**
 * End-to-end proof that a plain {@code @SpringBootTest} with no annotations from this starter fails on N+1,
 * passes on join fetch, and only gets a logged hint for an explicit loop.
 */
@ExtendWith(OutputCaptureExtension.class)
class ListenerFailsTestIT {

    @Test
    void whenSampleTestTriggersNPlusOne_shouldFailWithViolationsAndPassOtherwise() {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(UnawareSample.class))
                .execute();

        results.testEvents().assertThatEvents()
                .haveExactly(1, event(test("lazyAuthors()"), finishedWithFailure(
                        instanceOf(NPlusOneViolationsError.class),
                        message(m -> m.contains("lazy load of Author (proxy)") && m.contains("from author")))))
                .haveExactly(1, event(test("lazyAuthorsInsideTestTransaction()"), finishedWithFailure(
                        instanceOf(NPlusOneViolationsError.class))))
                .haveExactly(1, event(test("lazyAuthorsEachInOwnTransaction()"), finishedSuccessfully()))
                .haveExactly(1, event(test("lazyAuthorsEachInOwnTransactionInScope()"), finishedWithFailure(
                        instanceOf(NPlusOneViolationsError.class),
                        message(m -> m.contains("[describe all books]")))))
                .haveExactly(1, event(test("fetchedAuthors()"), finishedSuccessfully()))
                .haveExactly(1, event(test("booksOneByOne()"), finishedSuccessfully()))
                .haveExactly(1, event(test("cacheableCountries()"), finishedSuccessfully()));
    }

    @Test
    void whenTransactionalSampleUsesFailMode_shouldFailLoopAndPassFollowingTest() {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(TransactionalFailSample.class))
                .execute();

        results.testEvents().assertThatEvents()
                .haveExactly(1, event(test("aBooksOneByOne()"), finishedWithFailure(
                        instanceOf(NPlusOneViolationsError.class),
                        message(m -> m.contains("Repeated query") && m.contains("from book")))))
                .haveExactly(1, event(test("bFetchedAuthors()"), finishedSuccessfully()));
    }

    @Test
    void whenTransactionalSampleUsesDefaultLogMode_shouldWarnAndPass(CapturedOutput output) {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(TransactionalLogSample.class))
                .execute();

        results.testEvents().assertStatistics(statistics -> statistics.started(2).succeeded(2));
        assertThat(output.getOut()).contains("TransactionalLogSample.aBooksOneByOne: Repeated query")
                .doesNotContain("TransactionalLogSample.bFetchedAuthors: Repeated query");
    }

    @SpringBootTest(properties = "nplusone.explicit-queries=fail")
    @SuppressWarnings("java:S3577") // Executed only by the enclosing engine test.
    static class TransactionalFailSample extends TransactionalSample {
    }

    @SpringBootTest
    @SuppressWarnings("java:S3577") // Executed only by the enclosing engine test.
    static class TransactionalLogSample extends TransactionalSample {
    }

    @Transactional
    @TestMethodOrder(MethodOrderer.MethodName.class)
    abstract static class TransactionalSample {
        @Autowired
        private Library library;

        @Autowired
        private BookService bookService;

        private List<Long> bookIds;

        @BeforeTransaction
        void seed() {
            library.reset();
            bookIds = library.bookIds();
        }

        @Test
        void aBooksOneByOne() {
            assertThat(bookService.titlesOneByOne(bookIds)).hasSize(Library.AUTHORS);
        }

        @Test
        void bFetchedAuthors() {
            assertThat(bookService.titlesWithAuthorsFetched()).hasSize(Library.AUTHORS);
        }
    }
}
