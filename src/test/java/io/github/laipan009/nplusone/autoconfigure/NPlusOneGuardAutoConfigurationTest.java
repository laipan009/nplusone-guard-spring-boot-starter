package io.github.laipan009.nplusone.autoconfigure;

import io.github.laipan009.nplusone.core.CompositeInterceptor;
import io.github.laipan009.nplusone.core.CompositeStatementInspector;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import io.github.laipan009.nplusone.core.Violation;
import org.hibernate.Interceptor;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SessionEventSettings;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.internal.EmptyInterceptor;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NPlusOneGuardAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NPlusOneGuardAutoConfiguration.class));

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NPlusOneGuardAutoConfiguration.class));

    @Test
    void whenDefaults_shouldRegisterDetectorAsInspectorAndInterceptor() {
        runner.run(context -> {
            var detector = context.getBean(NPlusOneDetector.class);
            assertThat(detector.maxRepeats()).isEqualTo(2);

            Map<String, Object> hibernate = new HashMap<>();
            context.getBean(HibernatePropertiesCustomizer.class).customize(hibernate);
            assertThat(hibernate)
                    .containsEntry(JdbcSettings.STATEMENT_INSPECTOR, detector)
                    .containsEntry(SessionEventSettings.INTERCEPTOR, detector);
            assertThat(hibernate.get("hibernate.integrator_provider")).isInstanceOf(IntegratorProvider.class);
            assertThat(((IntegratorProvider) hibernate.get("hibernate.integrator_provider")).getIntegrators())
                    .hasSize(1);
        });
    }

    @Test
    void whenApplicationAlreadyConfiguredHibernateHooks_shouldChainInsteadOfReplacing() {
        runner.run(context -> {
            var detector = context.getBean(NPlusOneDetector.class);
            Integrator applicationIntegrator = mock(Integrator.class);
            Interceptor applicationInterceptor = new Interceptor() { };
            Map<String, Object> hibernate = new HashMap<>();
            hibernate.put(JdbcSettings.STATEMENT_INSPECTOR, HibernateSettingsTest.UpperCasing.class.getName());
            hibernate.put(SessionEventSettings.INTERCEPTOR, applicationInterceptor);
            hibernate.put("hibernate.integrator_provider", (IntegratorProvider) () -> List.of(applicationIntegrator));

            context.getBean(HibernatePropertiesCustomizer.class).customize(hibernate);

            var inspector = (CompositeStatementInspector) hibernate.get(JdbcSettings.STATEMENT_INSPECTOR);
            assertThat(inspector.inspectors()).hasSize(2).first().isSameAs(detector);
            assertThat(inspector.inspectors().get(1)).isInstanceOf(HibernateSettingsTest.UpperCasing.class);
            assertThat(inspector.inspect("select 1")).isEqualTo("SELECT 1");

            var interceptor = (CompositeInterceptor) hibernate.get(SessionEventSettings.INTERCEPTOR);
            assertThat(interceptor.interceptors()).containsExactly(detector, applicationInterceptor);

            var provider = (IntegratorProvider) hibernate.get("hibernate.integrator_provider");
            assertThat(provider.getIntegrators()).hasSize(2).first().isSameAs(applicationIntegrator);
        });
    }

    @ParameterizedTest
    @MethodSource("sessionInterceptorSettings")
    void whenSessionScopedInterceptorConfigured_shouldKeepDistinctDelegatesAndDetectQueries(
            Object setting, Interceptor factoryInterceptor) {
        runner.run(context -> {
            var detector = context.getBean(NPlusOneDetector.class);
            Map<String, Object> hibernate = new HashMap<>();
            hibernate.put(JdbcSettings.URL, "jdbc:h2:mem:session-interceptors");
            hibernate.put(SessionEventSettings.SESSION_SCOPED_INTERCEPTOR, setting);
            if (factoryInterceptor != null) {
                hibernate.put(SessionEventSettings.INTERCEPTOR, factoryInterceptor);
            }
            context.getBean(HibernatePropertiesCustomizer.class).customize(hibernate);

            var configuration = new Configuration();
            configuration.getProperties().putAll(hibernate);
            var selectedInterceptors = new ArrayList<Interceptor>();
            try (var factory = configuration.buildSessionFactory()) {
                for (int i = 0; i < 2; i++) {
                    try (var session = factory.openSession()) {
                        selectedInterceptors.add(session.unwrap(SharedSessionContractImplementor.class)
                                .getInterceptor());
                        var transaction = session.beginTransaction();
                        for (int j = 0; j < 3; j++) {
                            assertThat(session.createNativeQuery("select 1", Integer.class).getSingleResult())
                                    .isEqualTo(1);
                        }
                        transaction.commit();
                    }
                }
            }

            assertThat(selectedInterceptors).allSatisfy(interceptor -> {
                assertThat(interceptor).isInstanceOf(CompositeInterceptor.class);
                var delegates = ((CompositeInterceptor) interceptor).interceptors();
                assertThat(delegates).hasSize(2).first().isSameAs(detector);
                assertThat(delegates.get(1)).isInstanceOfSatisfying(RecordingInterceptor.class, application -> {
                    assertThat(application.transactionsStarted).isEqualTo(1);
                    assertThat(application.transactionsCompleted).isEqualTo(1);
                });
            });
            assertThat(((CompositeInterceptor) selectedInterceptors.get(0)).interceptors().get(1))
                    .isNotSameAs(((CompositeInterceptor) selectedInterceptors.get(1)).interceptors().get(1));
            assertThat(detector.drainViolations()).hasSize(2).allSatisfy(violation -> {
                assertThat(violation.kind()).isEqualTo(Violation.Kind.EXPLICIT_QUERY);
                assertThat(violation.repeats()).isEqualTo(3);
            });
        });
    }

    static Stream<Arguments> sessionInterceptorSettings() {
        return Stream.of((Supplier<Interceptor>) RecordingInterceptor::new,
                        RecordingInterceptor.class, RecordingInterceptor.class.getName())
                .flatMap(setting -> Stream.of(Arguments.of(setting, null),
                        Arguments.of(setting, EmptyInterceptor.INSTANCE)));
    }

    @Test
    void whenFactoryAndSessionScopedInterceptorsConfigured_shouldPreserveFactoryPrecedence() {
        runner.run(context -> {
            var detector = context.getBean(NPlusOneDetector.class);
            var application = new RecordingInterceptor();
            var supplierCalls = new AtomicInteger();
            Map<String, Object> hibernate = new HashMap<>();
            hibernate.put(JdbcSettings.URL, "jdbc:h2:mem:factory-interceptor");
            hibernate.put(SessionEventSettings.INTERCEPTOR, application);
            hibernate.put(SessionEventSettings.SESSION_SCOPED_INTERCEPTOR, (Supplier<Interceptor>) () -> {
                supplierCalls.incrementAndGet();
                return new RecordingInterceptor();
            });
            context.getBean(HibernatePropertiesCustomizer.class).customize(hibernate);

            var configuration = new Configuration();
            configuration.getProperties().putAll(hibernate);
            try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
                var selected = session.unwrap(SharedSessionContractImplementor.class).getInterceptor();
                assertThat(selected).isInstanceOfSatisfying(CompositeInterceptor.class,
                        interceptor -> assertThat(interceptor.interceptors()).containsExactly(detector, application));
                session.beginTransaction().commit();
            }
            assertThat(supplierCalls).hasValue(0);
            assertThat(application.transactionsStarted).isEqualTo(1);
            assertThat(application.transactionsCompleted).isEqualTo(1);
        });
    }

    @Test
    void whenSessionInterceptorSupplierReturnsNull_shouldStillInstallDetector() {
        runner.run(context -> {
            Map<String, Object> hibernate = new HashMap<>();
            hibernate.put(JdbcSettings.URL, "jdbc:h2:mem:null-interceptor");
            hibernate.put(SessionEventSettings.SESSION_SCOPED_INTERCEPTOR, (Supplier<Interceptor>) () -> null);
            context.getBean(HibernatePropertiesCustomizer.class).customize(hibernate);

            var configuration = new Configuration();
            configuration.getProperties().putAll(hibernate);
            try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
                assertThat(session.unwrap(SharedSessionContractImplementor.class).getInterceptor())
                        .isSameAs(context.getBean(NPlusOneDetector.class));
            }
        });
    }

    public static class RecordingInterceptor implements Interceptor {
        private int transactionsStarted;
        private int transactionsCompleted;

        @Override
        public void afterTransactionBegin(Transaction transaction) {
            transactionsStarted++;
        }

        @Override
        public void afterTransactionCompletion(Transaction transaction) {
            transactionsCompleted++;
        }
    }

    @Test
    void whenPropertiesSet_shouldApplyThem() {
        runner.withPropertyValues("nplusone.max-repeats=5", "nplusone.allowlist=from audit_log,from outbox",
                        "nplusone.explicit-queries=fail")
                .run(context -> {
                    assertThat(context.getBean(NPlusOneDetector.class).maxRepeats()).isEqualTo(5);
                    var properties = context.getBean(NPlusOneGuardProperties.class);
                    assertThat(properties.getAllowlist()).containsExactly("from audit_log", "from outbox");
                    assertThat(properties.getExplicitQueries()).isEqualTo(ExplicitQueriesMode.FAIL);
                });
    }

    @Test
    void whenDisabled_shouldRegisterNothing() {
        runner.withPropertyValues("nplusone.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(NPlusOneDetector.class)
                .doesNotHaveBean(HibernatePropertiesCustomizer.class));
    }

    @Test
    void whenUserDefinesDetector_shouldUseIt() {
        var custom = new NPlusOneDetector(9, List.of());
        runner.withBean(NPlusOneDetector.class, () -> custom)
                .run(context -> assertThat(context.getBean(NPlusOneDetector.class)).isSameAs(custom));
    }

    @Test
    void whenServletWebApplication_shouldRegisterRequestScopeFilterFirst() {
        webRunner.run(context -> {
            var registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(NPlusOneRequestScopeFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);
        });
    }

    @Test
    void whenRequestScopeDisabled_shouldNotRegisterFilter() {
        webRunner.withPropertyValues("nplusone.request-scope=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void whenNotWebApplication_shouldNotRegisterFilter() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
