package io.github.laipan009.nplusone.test;

import io.github.laipan009.nplusone.autoconfigure.ExplicitQueriesMode;
import io.github.laipan009.nplusone.autoconfigure.NPlusOneGuardProperties;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import io.github.laipan009.nplusone.core.NPlusOneViolationsError;
import io.github.laipan009.nplusone.core.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

import java.util.ArrayList;

/**
 * Drops violations left over from previous tests before each test method and, after it, fails the test on
 * implicit N+1, logs loads of cacheable data, and reports explicit repeats according to
 * {@code nplusone.explicit-queries}. Registered through
 * {@code META-INF/spring.factories}, so every Spring test picks it up without annotations. Tests whose context has
 * no detector bean are left alone.
 */
public class NPlusOneTestExecutionListener extends AbstractTestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneTestExecutionListener.class);

    @Override
    public int getOrder() {
        // After callbacks run in reverse order: evaluate only after the test transaction has completed.
        return TransactionalTestExecutionListener.ORDER - 1;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        var detector = detector(testContext);
        if (detector != null) {
            detector.drainViolations();
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        var detector = detector(testContext);
        if (detector == null) {
            return;
        }
        var violations = detector.drainViolations();
        if (violations.isEmpty()) {
            return;
        }
        var properties = properties(testContext);
        var test = testName(testContext);
        var failing = new ArrayList<Violation>();
        var cacheable = new ArrayList<Violation>();
        var advisory = new ArrayList<Violation>();
        for (var violation : violations) {
            switch (violation.kind()) {
                case IMPLICIT_LOAD -> failing.add(violation);
                case CACHEABLE_LOAD -> cacheable.add(violation);
                case EXPLICIT_QUERY -> {
                    if (properties.getExplicitQueries() == ExplicitQueriesMode.FAIL) {
                        failing.add(violation);
                    } else if (properties.getExplicitQueries() == ExplicitQueriesMode.LOG) {
                        advisory.add(violation);
                    }
                }
            }
        }
        if (!cacheable.isEmpty() && log.isWarnEnabled()) {
            log.warn("{}: {}", test, NPlusOneViolationsError.describeCacheable(cacheable, detector.maxRepeats()));
        }
        if (!advisory.isEmpty() && log.isWarnEnabled()) {
            log.warn("{}: {}", test, NPlusOneViolationsError.describeExplicit(advisory, detector.maxRepeats()));
        }
        if (failing.isEmpty()) {
            return;
        }
        var exception = new NPlusOneViolationsError(failing, detector.maxRepeats());
        if (properties.isFailTest()) {
            throw exception;
        }
        log.warn("{}: {}", test, exception.getMessage());
    }

    private static String testName(TestContext testContext) {
        return testContext.getTestClass().getSimpleName() + "." + testContext.getTestMethod().getName();
    }

    private static NPlusOneDetector detector(TestContext testContext) {
        return testContext.getApplicationContext().getBeanProvider(NPlusOneDetector.class).getIfAvailable();
    }

    private static NPlusOneGuardProperties properties(TestContext testContext) {
        return testContext.getApplicationContext().getBeanProvider(NPlusOneGuardProperties.class)
                .getIfAvailable(NPlusOneGuardProperties::new);
    }
}
