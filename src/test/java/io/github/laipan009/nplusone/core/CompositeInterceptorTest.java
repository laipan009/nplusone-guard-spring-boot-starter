package io.github.laipan009.nplusone.core;

import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeInterceptorTest {

    private final List<String> calls = new ArrayList<>();

    private Interceptor recording(String name, boolean modifies) {
        return new Interceptor() {
            @Override
            public boolean onLoad(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
                calls.add(name + ".onLoad");
                return modifies;
            }

            @Override
            public void afterTransactionBegin(org.hibernate.Transaction tx) {
                calls.add(name + ".afterTransactionBegin");
            }

            @Override
            public void postFlush(Iterator<Object> entities) {
                var count = 0;
                while (entities.hasNext()) {
                    entities.next();
                    count++;
                }
                calls.add(name + ".postFlush(" + count + ")");
            }

            @Override
            public String getEntityName(Object object) {
                return modifies ? name : null;
            }
        };
    }

    @Test
    void whenCallbackFires_shouldReachEveryDelegateInOrder() {
        var composite = new CompositeInterceptor(List.of(recording("first", false), recording("second", true)));

        composite.afterTransactionBegin(null);
        var modified = composite.onLoad(new Object(), 1L, new Object[0], new String[0], new Type[0]);

        assertThat(calls).containsExactly("first.afterTransactionBegin", "second.afterTransactionBegin",
                "first.onLoad", "second.onLoad");
        assertThat(modified).as("any delegate modifying the state counts").isTrue();
    }

    @Test
    void whenIteratorCallbackFires_shouldGiveEveryDelegateAllEntities() {
        var composite = new CompositeInterceptor(List.of(recording("first", false), recording("second", false)));

        composite.postFlush(List.<Object>of("a", "b", "c").iterator());

        assertThat(calls).containsExactly("first.postFlush(3)", "second.postFlush(3)");
    }

    @Test
    void whenDelegatesAnswerObjectResults_shouldReturnFirstNonNull() {
        var composite = new CompositeInterceptor(List.of(recording("first", false), recording("second", true)));

        assertThat(composite.getEntityName(new Object())).isEqualTo("second");
        assertThat(composite.isTransient(new Object())).isNull();
    }
}
