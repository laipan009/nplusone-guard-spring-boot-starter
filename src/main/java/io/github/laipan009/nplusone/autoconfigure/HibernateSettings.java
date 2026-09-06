package io.github.laipan009.nplusone.autoconfigure;

import org.springframework.beans.BeanUtils;
import org.springframework.util.ClassUtils;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves a Hibernate setting that may hold an instance, a {@link Class} or a class name, the three forms
 * Hibernate itself accepts for interceptors, statement inspectors and integrator providers.
 */
final class HibernateSettings {

    private HibernateSettings() {
    }

    static <T> Optional<T> resolve(Object value, Class<T> type) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        if (value instanceof Class<?> clazz) {
            return Optional.of(instantiate(clazz, type));
        }
        if (value instanceof String className) {
            var clazz = ClassUtils.resolveClassName(className.trim(), HibernateSettings.class.getClassLoader());
            return Optional.of(instantiate(clazz, type));
        }
        throw new IllegalArgumentException("Cannot resolve " + type.getSimpleName() + " from " + value);
    }

    /** Session-scoped interceptors accept a supplier, a Class or a class name, instantiated per session. */
    static <T> Supplier<T> resolveSupplier(Object value, Class<T> type) {
        if (value instanceof Supplier<?> supplier) {
            return () -> type.cast(supplier.get());
        }
        if (value instanceof Class<?> clazz) {
            return () -> instantiate(clazz, type);
        }
        if (value instanceof String className) {
            var clazz = ClassUtils.resolveClassName(className.trim(), HibernateSettings.class.getClassLoader());
            return () -> instantiate(clazz, type);
        }
        throw new IllegalArgumentException("Cannot resolve " + type.getSimpleName() + " supplier from " + value);
    }

    private static <T> T instantiate(Class<?> clazz, Class<T> type) {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " is not a " + type.getSimpleName());
        }
        return type.cast(BeanUtils.instantiateClass(clazz));
    }
}
