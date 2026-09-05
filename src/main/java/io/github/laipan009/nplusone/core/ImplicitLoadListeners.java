package io.github.laipan009.nplusone.core;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.InitializeCollectionEvent;
import org.hibernate.event.spi.InitializeCollectionEventListener;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Hibernate event listeners that bracket implicit loads so that {@link NPlusOneDetector} can attribute the
 * statements Hibernate issues in between.
 *
 * <p>Two listener classes are needed because Hibernate rejects the same listener class twice in one event group:
 * {@link Start} is prepended and runs before Hibernate's own listener performs the load, {@link End} is appended
 * and runs after it. Registered by the {@link Integrator} returned from {@link #integrator(NPlusOneDetector)}.
 *
 * <p>The integrator also reads the mapping metadata for entities and collections declared cacheable
 * ({@code @Cacheable}, {@code @Cache}). Their loads are reported, not failed: a warm second-level cache serves them
 * without statements in production, whatever the test profile does with the cache.
 */
public final class ImplicitLoadListeners {

    private ImplicitLoadListeners() {
    }

    public static Integrator integrator(NPlusOneDetector detector) {
        return new Integrator() {
            @Override
            public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                                  SessionFactoryImplementor sessionFactory) {
                var registry = sessionFactory.getEventEngine().getListenerRegistry();
                var start = new Start(detector, cacheableNames(metadata));
                var end = new End(detector);
                registry.getEventListenerGroup(EventType.LOAD).prependListener(start);
                registry.getEventListenerGroup(EventType.LOAD).appendListener(end);
                registry.getEventListenerGroup(EventType.INIT_COLLECTION).prependListener(start);
                registry.getEventListenerGroup(EventType.INIT_COLLECTION).appendListener(end);
            }

            @Override
            public void disintegrate(SessionFactoryImplementor sessionFactory,
                                     SessionFactoryServiceRegistry serviceRegistry) {
                // listeners die with the SessionFactory; nothing to release
            }
        };
    }

    /** Entity names and collection roles the mapping declares cacheable, whether or not a cache is configured. */
    static Set<String> cacheableNames(Metadata metadata) {
        var names = new HashSet<String>();
        for (var entity : metadata.getEntityBindings()) {
            if (entity.isCached() || entity.getCacheConcurrencyStrategy() != null) {
                names.add(entity.getEntityName());
            }
        }
        for (var collection : metadata.getCollectionBindings()) {
            if (collection.getCacheConcurrencyStrategy() != null) {
                names.add(collection.getRole());
            }
        }
        return names;
    }

    /**
     * Describes the load if it is implicit. Explicit {@code find} and {@code getReference} calls arrive as
     * {@link LoadEventListener#LOAD} or {@link LoadEventListener#GET}; proxy initialization arrives as
     * {@link LoadEventListener#IMMEDIATE_LOAD}; an EAGER association resolved after a query by a separate select
     * arrives as {@link LoadEventListener#INTERNAL_LOAD_EAGER}. Lazy internal loads only create proxies and issue
     * no statement.
     */
    static Optional<String> subject(LoadEvent event, LoadEventListener.LoadType loadType) {
        if (loadType == LoadEventListener.IMMEDIATE_LOAD) {
            return Optional.of("lazy load of " + simpleName(event.getEntityClassName()) + " (proxy)");
        }
        if (loadType == LoadEventListener.INTERNAL_LOAD_EAGER) {
            return Optional.of("eager select of " + simpleName(event.getEntityClassName()));
        }
        return Optional.empty();
    }

    static String subject(InitializeCollectionEvent event) {
        var role = event.getCollection().getRole();
        var owner = role.lastIndexOf('.', role.lastIndexOf('.') - 1);
        return "lazy load of collection " + (owner < 0 ? role : role.substring(owner + 1));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    static final class Start implements LoadEventListener, InitializeCollectionEventListener {

        private final NPlusOneDetector detector;
        private final Set<String> cacheableNames;

        Start(NPlusOneDetector detector, Set<String> cacheableNames) {
            this.detector = detector;
            this.cacheableNames = Set.copyOf(cacheableNames);
        }

        @Override
        public void onLoad(LoadEvent event, LoadType loadType) {
            subject(event, loadType).ifPresent(subject -> detector.beginImplicitLoad(
                    event.getSession().getSessionIdentifier(), subject,
                    cacheableNames.contains(event.getEntityClassName())));
        }

        @Override
        public void onInitializeCollection(InitializeCollectionEvent event) {
            detector.beginImplicitLoad(event.getSession().getSessionIdentifier(), subject(event),
                    cacheableNames.contains(event.getCollection().getRole()));
        }
    }

    static final class End implements LoadEventListener, InitializeCollectionEventListener {

        private final NPlusOneDetector detector;

        End(NPlusOneDetector detector) {
            this.detector = detector;
        }

        @Override
        public void onLoad(LoadEvent event, LoadType loadType) {
            subject(event, loadType).ifPresent(subject ->
                    detector.endImplicitLoad(event.getSession().getSessionIdentifier(), subject));
        }

        @Override
        public void onInitializeCollection(InitializeCollectionEvent event) {
            detector.endImplicitLoad(event.getSession().getSessionIdentifier(), subject(event));
        }
    }
}
