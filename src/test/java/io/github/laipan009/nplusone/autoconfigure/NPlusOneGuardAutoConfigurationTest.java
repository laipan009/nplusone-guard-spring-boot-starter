package io.github.laipan009.nplusone.autoconfigure;

import io.github.laipan009.nplusone.core.CompositeInterceptor;
import io.github.laipan009.nplusone.core.CompositeStatementInspector;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import org.hibernate.Interceptor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SessionEventSettings;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
