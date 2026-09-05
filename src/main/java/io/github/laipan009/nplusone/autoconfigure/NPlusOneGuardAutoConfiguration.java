package io.github.laipan009.nplusone.autoconfigure;

import io.github.laipan009.nplusone.core.CompositeInterceptor;
import io.github.laipan009.nplusone.core.CompositeStatementInspector;
import io.github.laipan009.nplusone.core.ImplicitLoadListeners;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import org.hibernate.Interceptor;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SessionEventSettings;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({StatementInspector.class, HibernatePropertiesCustomizer.class})
@ConditionalOnProperty(prefix = "nplusone", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NPlusOneGuardProperties.class)
public class NPlusOneGuardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneGuardAutoConfiguration.class);

    /** Read by Hibernate's EntityManagerFactoryBuilderImpl; accepts an IntegratorProvider instance. */
    static final String INTEGRATOR_PROVIDER = "hibernate.integrator_provider";

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneDetector nPlusOneDetector(NPlusOneGuardProperties properties) {
        return new NPlusOneDetector(properties.getMaxRepeats(), properties.getAllowlist());
    }

    /**
     * Runs after every other customizer so that an inspector, interceptor or integrator provider the application
     * configured, through {@code spring.jpa.properties} or another customizer, is kept and chained rather than lost.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public HibernatePropertiesCustomizer nPlusOneHibernatePropertiesCustomizer(NPlusOneDetector detector) {
        return properties -> {
            chainStatementInspector(properties, detector);
            chainInterceptor(properties, detector);
            chainIntegrator(properties, ImplicitLoadListeners.integrator(detector));
        };
    }

    private static void chainStatementInspector(Map<String, Object> properties, NPlusOneDetector detector) {
        var existing = HibernateSettings.resolve(properties.get(JdbcSettings.STATEMENT_INSPECTOR),
                StatementInspector.class);
        properties.put(JdbcSettings.STATEMENT_INSPECTOR, existing
                .map(inspector -> chained(JdbcSettings.STATEMENT_INSPECTOR, inspector,
                        (StatementInspector) new CompositeStatementInspector(List.of(detector, inspector))))
                .orElse(detector));
    }

    private static void chainInterceptor(Map<String, Object> properties, NPlusOneDetector detector) {
        var existing = HibernateSettings.resolve(properties.get(SessionEventSettings.INTERCEPTOR), Interceptor.class);
        properties.put(SessionEventSettings.INTERCEPTOR, existing
                .map(interceptor -> chained(SessionEventSettings.INTERCEPTOR, interceptor,
                        (Interceptor) new CompositeInterceptor(List.of(detector, interceptor))))
                .orElse(detector));
    }

    private static void chainIntegrator(Map<String, Object> properties, Integrator integrator) {
        var existing = HibernateSettings.resolve(properties.get(INTEGRATOR_PROVIDER), IntegratorProvider.class);
        IntegratorProvider provider = existing
                .map(previous -> chained(INTEGRATOR_PROVIDER, previous, (IntegratorProvider) () -> {
                    var integrators = new ArrayList<>(previous.getIntegrators());
                    integrators.add(integrator);
                    return integrators;
                }))
                .orElse(() -> List.of(integrator));
        properties.put(INTEGRATOR_PROVIDER, provider);
    }

    private static <T> T chained(String key, Object previous, T composite) {
        log.info("N+1 guard chained to the existing Hibernate {}: {}", key, previous.getClass().getName());
        return composite;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(OncePerRequestFilter.class)
    @ConditionalOnProperty(prefix = "nplusone", name = "request-scope", havingValue = "true", matchIfMissing = true)
    static class RequestScopeConfiguration {

        @Bean
        public FilterRegistrationBean<NPlusOneRequestScopeFilter> nPlusOneRequestScopeFilter(NPlusOneDetector detector) {
            var registration = new FilterRegistrationBean<>(new NPlusOneRequestScopeFilter(detector));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
