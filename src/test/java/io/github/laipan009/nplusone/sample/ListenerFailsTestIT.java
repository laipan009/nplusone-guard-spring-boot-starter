package io.github.laipan009.nplusone.sample;

import io.github.laipan009.nplusone.core.NPlusOneViolationsError;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

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
}
