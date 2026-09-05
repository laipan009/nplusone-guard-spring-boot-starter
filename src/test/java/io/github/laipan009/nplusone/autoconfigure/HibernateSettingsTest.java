package io.github.laipan009.nplusone.autoconfigure;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HibernateSettingsTest {

    public static class UpperCasing implements StatementInspector {
        @Override
        public String inspect(String sql) {
            return sql.toUpperCase();
        }
    }

    @Test
    void whenValueMissing_shouldBeEmpty() {
        assertThat(HibernateSettings.resolve(null, StatementInspector.class)).isEmpty();
        assertThat(HibernateSettings.resolve("  ", StatementInspector.class)).isEmpty();
    }

    @Test
    void whenValueIsInstance_shouldReturnIt() {
        var instance = new UpperCasing();

        assertThat(HibernateSettings.resolve(instance, StatementInspector.class)).containsSame(instance);
    }

    @Test
    void whenValueIsClassOrClassName_shouldInstantiate() {
        assertThat(HibernateSettings.resolve(UpperCasing.class, StatementInspector.class))
                .get().isInstanceOf(UpperCasing.class);
        assertThat(HibernateSettings.resolve(UpperCasing.class.getName(), StatementInspector.class))
                .get().isInstanceOf(UpperCasing.class);
    }

    @Test
    void whenValueIsWrongType_shouldReject() {
        assertThatThrownBy(() -> HibernateSettings.resolve(String.class, StatementInspector.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HibernateSettings.resolve(42, StatementInspector.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
