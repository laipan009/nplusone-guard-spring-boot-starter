package io.github.laipan009.nplusone.core;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.InitializeCollectionEvent;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Hibernate's session interfaces carry annotations Mockito cannot load in a plain unit test, so the session and the
 * collection are JDK proxies answering only what the listeners ask.
 */
class ImplicitLoadListenersTest {

    private static final String SQL = "select a.id from author a where a.id=?";

    private final NPlusOneDetector detector = new NPlusOneDetector(2, List.of());
    private final UUID sessionId = UUID.randomUUID();
    /** Empty persistence context: the collection event constructor looks up its owner there and accepts null. */
    private final PersistenceContext persistenceContext = (PersistenceContext) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{PersistenceContext.class}, (proxy, method, args) -> null);
    private final EventSource session = (EventSource) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{EventSource.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getSessionIdentifier" -> sessionId;
                case "getPersistenceContextInternal" -> persistenceContext;
                default -> null;
            });
    private final ImplicitLoadListeners.Start start = new ImplicitLoadListeners.Start(detector,
            Set.of("io.github.laipan009.nplusone.sample.Country"));
    private final ImplicitLoadListeners.End end = new ImplicitLoadListeners.End(detector);

    private void load(LoadEventListener.LoadType type, int times) {
        load("io.github.laipan009.nplusone.sample.Author", type, times);
    }

    private void load(String entityName, LoadEventListener.LoadType type, int times) {
        var event = new LoadEvent(1L, entityName, false, session, null);
        for (int i = 0; i < times; i++) {
            start.onLoad(event, type);
            detector.inspect(SQL);
            end.onLoad(event, type);
        }
    }

    @Test
    void whenProxyIsInitialized_shouldAttributeStatementsToEntity() {
        load(LoadEventListener.IMMEDIATE_LOAD, 3);

        assertThat(detector.drainViolations()).extracting(Violation::kind, Violation::subject, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.IMPLICIT_LOAD, "lazy load of Author (proxy)", 3));
    }

    @Test
    void whenEagerAssociationIsSelected_shouldAttributeStatementsToEntity() {
        load(LoadEventListener.INTERNAL_LOAD_EAGER, 3);

        assertThat(detector.drainViolations()).extracting(Violation::subject)
                .containsExactly("eager select of Author");
    }

    @Test
    void whenApplicationFindsEntityExplicitly_shouldNotTreatItAsImplicit() {
        detector.afterTransactionBegin(null);
        load(LoadEventListener.LOAD, 3);
        load(LoadEventListener.GET, 3);
        load(LoadEventListener.INTERNAL_LOAD_LAZY, 3);
        detector.afterTransactionCompletion(null);

        assertThat(detector.drainViolations()).extracting(Violation::kind)
                .containsOnly(Violation.Kind.EXPLICIT_QUERY);
    }

    @Test
    void whenCacheableEntityIsLoadedImplicitly_shouldReportCacheableLoad() {
        load("io.github.laipan009.nplusone.sample.Country", LoadEventListener.IMMEDIATE_LOAD, 3);

        assertThat(detector.drainViolations()).extracting(Violation::kind, Violation::subject)
                .containsExactly(tuple(Violation.Kind.CACHEABLE_LOAD, "lazy load of Country (proxy)"));
    }

    @Test
    void whenCollectionIsInitialized_shouldAttributeStatementsToRole() {
        var collection = (PersistentCollection<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PersistentCollection.class},
                (proxy, method, args) -> method.getName().equals("getRole")
                        ? "io.github.laipan009.nplusone.sample.Author.books" : null);
        var event = new InitializeCollectionEvent(collection, session);

        for (int i = 0; i < 3; i++) {
            start.onInitializeCollection(event);
            detector.inspect("select b.id from book b where b.author_id=?");
            end.onInitializeCollection(event);
        }

        assertThat(detector.drainViolations()).extracting(Violation::subject)
                .containsExactly("lazy load of collection Author.books");
    }
}
