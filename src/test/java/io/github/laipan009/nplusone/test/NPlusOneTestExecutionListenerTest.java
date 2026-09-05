package io.github.laipan009.nplusone.test;

import io.github.laipan009.nplusone.autoconfigure.ExplicitQueriesMode;
import io.github.laipan009.nplusone.autoconfigure.NPlusOneGuardProperties;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import io.github.laipan009.nplusone.core.NPlusOneViolationsError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.TestContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NPlusOneTestExecutionListenerTest {

    private static final String SQL = "select a.id from author a where a.id=?";

    private final NPlusOneTestExecutionListener listener = new NPlusOneTestExecutionListener();
    private final NPlusOneDetector detector = new NPlusOneDetector(2, List.of());
    private final NPlusOneGuardProperties properties = new NPlusOneGuardProperties();
    private final GenericApplicationContext applicationContext = new GenericApplicationContext();
    private final TestContext testContext = mock(TestContext.class);

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        when(testContext.getApplicationContext()).thenReturn(applicationContext);
        when(testContext.getTestClass()).thenAnswer(invocation -> getClass());
        when(testContext.getTestMethod()).thenReturn(getClass().getDeclaredMethod("setUp"));
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
    }

    private void withGuardBeans() {
        applicationContext.registerBean(NPlusOneDetector.class, () -> detector);
        applicationContext.registerBean(NPlusOneGuardProperties.class, () -> properties);
        applicationContext.refresh();
    }

    private void recordImplicitViolation() {
        var session = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            detector.beginImplicitLoad(session, "lazy load of Author (proxy)");
            detector.inspect(SQL);
            detector.endImplicitLoad(session, "lazy load of Author (proxy)");
        }
    }

    private void recordExplicitViolation() {
        detector.afterTransactionBegin(null);
        for (int i = 0; i < 3; i++) {
            detector.inspect(SQL);
        }
        detector.afterTransactionCompletion(null);
    }

    @Test
    void whenImplicitViolationRecorded_shouldFailAfterTestMethod() {
        withGuardBeans();
        recordImplicitViolation();

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(NPlusOneViolationsError.class)
                .hasMessageContaining("3 x lazy load of Author (proxy)");
        assertThat(detector.drainViolations()).as("violations are consumed").isEmpty();
    }

    @Test
    void whenExplicitViolationRecordedInDefaultLogMode_shouldPass() {
        withGuardBeans();
        recordExplicitViolation();

        assertThatCode(() -> listener.afterTestMethod(testContext)).doesNotThrowAnyException();
        assertThat(detector.drainViolations()).isEmpty();
    }

    @Test
    void whenExplicitViolationRecordedInFailMode_shouldFail() {
        properties.setExplicitQueries(ExplicitQueriesMode.FAIL);
        withGuardBeans();
        recordExplicitViolation();

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(NPlusOneViolationsError.class)
                .hasMessageContaining("Repeated query");
    }

    @Test
    void whenExplicitViolationRecordedInOffMode_shouldPassSilently() {
        properties.setExplicitQueries(ExplicitQueriesMode.OFF);
        withGuardBeans();
        recordExplicitViolation();

        assertThatCode(() -> listener.afterTestMethod(testContext)).doesNotThrowAnyException();
    }

    @Test
    void whenTestWrapsCallInScope_shouldFailWithThatScope() {
        withGuardBeans();
        listener.beforeTestMethod(testContext);
        detector.inScope("nightly export", () -> {
            for (int i = 0; i < 3; i++) {
                var session = UUID.randomUUID();
                detector.beginImplicitLoad(session, "lazy load of Author (proxy)");
                detector.inspect(SQL);
                detector.endImplicitLoad(session, "lazy load of Author (proxy)");
            }
        });

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(NPlusOneViolationsError.class)
                .hasMessageContaining("[nightly export]");
    }

    @Test
    void whenNoViolations_shouldPass() {
        withGuardBeans();

        assertThatCode(() -> listener.afterTestMethod(testContext)).doesNotThrowAnyException();
    }

    @Test
    void whenFailTestDisabled_shouldOnlyLogAndConsumeViolations() {
        properties.setFailTest(false);
        withGuardBeans();
        recordImplicitViolation();

        assertThatCode(() -> listener.afterTestMethod(testContext)).doesNotThrowAnyException();
        assertThat(detector.drainViolations()).isEmpty();
    }

    @Test
    void whenViolationsLeftFromPreviousTest_shouldDropThemBeforeTestMethod() {
        withGuardBeans();
        recordImplicitViolation();

        listener.beforeTestMethod(testContext);

        assertThatCode(() -> listener.afterTestMethod(testContext)).doesNotThrowAnyException();
    }

    @Test
    void whenContextHasNoDetector_shouldDoNothing() {
        applicationContext.refresh();

        assertThatCode(() -> {
            listener.beforeTestMethod(testContext);
            listener.afterTestMethod(testContext);
        }).doesNotThrowAnyException();
    }
}
