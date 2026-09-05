package io.github.laipan009.nplusone.core;

import org.hibernate.CallbackException;
import org.hibernate.Interceptor;
import org.hibernate.Transaction;
import org.hibernate.metamodel.RepresentationMode;
import org.hibernate.metamodel.spi.EntityRepresentationStrategy;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Forwards every {@link Interceptor} callback to each delegate, so that the interceptor the application had
 * configured before the starter keeps working next to the detector. Boolean results are combined with OR
 * (any delegate that modified the state), object results take the first non-null answer.
 *
 * <p>The deprecated overloads with a {@link Serializable} id are routed to the {@link Object} versions, so the
 * composite behaves the same whichever overload the caller resolves to.
 */
public record CompositeInterceptor(List<Interceptor> interceptors) implements Interceptor {

    public CompositeInterceptor {
        interceptors = List.copyOf(interceptors);
    }

    private boolean any(Predicate<Interceptor> call) {
        var modified = false;
        for (var interceptor : interceptors) {
            modified |= call.test(interceptor);
        }
        return modified;
    }

    private <T> T first(Function<Interceptor, T> call) {
        for (var interceptor : interceptors) {
            var result = call.apply(interceptor);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public boolean onLoad(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
            throws CallbackException {
        return any(i -> i.onLoad(entity, id, state, propertyNames, types));
    }

    @Override
    public boolean onPersist(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
            throws CallbackException {
        return any(i -> i.onPersist(entity, id, state, propertyNames, types));
    }

    @Override
    public void onRemove(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
            throws CallbackException {
        interceptors.forEach(i -> i.onRemove(entity, id, state, propertyNames, types));
    }

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState, Object[] previousState,
                                String[] propertyNames, Type[] types) throws CallbackException {
        return any(i -> i.onFlushDirty(entity, id, currentState, previousState, propertyNames, types));
    }

    @Override
    public boolean onSave(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
            throws CallbackException {
        return any(i -> i.onSave(entity, id, state, propertyNames, types));
    }

    @Override
    public void onDelete(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
            throws CallbackException {
        interceptors.forEach(i -> i.onDelete(entity, id, state, propertyNames, types));
    }

    @Override
    public void onDelete(Object entity, Object id, String[] propertyNames, Type[] types) {
        interceptors.forEach(i -> i.onDelete(entity, id, propertyNames, types));
    }

    @Override
    public void onCollectionRecreate(Object collection, Object key) throws CallbackException {
        interceptors.forEach(i -> i.onCollectionRecreate(collection, key));
    }

    @Override
    public void onCollectionRemove(Object collection, Object key) throws CallbackException {
        interceptors.forEach(i -> i.onCollectionRemove(collection, key));
    }

    @Override
    public void onCollectionUpdate(Object collection, Object key) throws CallbackException {
        interceptors.forEach(i -> i.onCollectionUpdate(collection, key));
    }

    @Override
    public void preFlush(Iterator<Object> entities) throws CallbackException {
        var snapshot = snapshot(entities);
        interceptors.forEach(i -> i.preFlush(snapshot.iterator()));
    }

    @Override
    public void postFlush(Iterator<Object> entities) throws CallbackException {
        var snapshot = snapshot(entities);
        interceptors.forEach(i -> i.postFlush(snapshot.iterator()));
    }

    private static List<Object> snapshot(Iterator<Object> entities) {
        var list = new ArrayList<>();
        entities.forEachRemaining(list::add);
        return list;
    }

    @Override
    public Boolean isTransient(Object entity) {
        return first(i -> i.isTransient(entity));
    }

    @Override
    public int[] findDirty(Object entity, Object id, Object[] currentState, Object[] previousState,
                           String[] propertyNames, Type[] types) {
        return first(i -> i.findDirty(entity, id, currentState, previousState, propertyNames, types));
    }

    @Override
    public Object instantiate(String entityName, EntityRepresentationStrategy representationStrategy, Object id)
            throws CallbackException {
        return first(i -> i.instantiate(entityName, representationStrategy, id));
    }

    @Override
    public Object instantiate(String entityName, RepresentationMode representationMode, Object id)
            throws CallbackException {
        return first(i -> i.instantiate(entityName, representationMode, id));
    }

    @Override
    public String getEntityName(Object object) throws CallbackException {
        return first(i -> i.getEntityName(object));
    }

    @Override
    public Object getEntity(String entityName, Object id) throws CallbackException {
        return first(i -> i.getEntity(entityName, id));
    }

    @Override
    public void afterTransactionBegin(Transaction tx) {
        interceptors.forEach(i -> i.afterTransactionBegin(tx));
    }

    @Override
    public void beforeTransactionCompletion(Transaction tx) {
        interceptors.forEach(i -> i.beforeTransactionCompletion(tx));
    }

    @Override
    public void afterTransactionCompletion(Transaction tx) {
        interceptors.forEach(i -> i.afterTransactionCompletion(tx));
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onLoad(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {
        return onLoad(entity, (Object) id, state, propertyNames, types);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onFlushDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
                                String[] propertyNames, Type[] types) {
        return onFlushDirty(entity, (Object) id, currentState, previousState, propertyNames, types);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onSave(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {
        return onSave(entity, (Object) id, state, propertyNames, types);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDelete(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {
        onDelete(entity, (Object) id, state, propertyNames, types);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCollectionRecreate(Object collection, Serializable key) {
        onCollectionRecreate(collection, (Object) key);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCollectionRemove(Object collection, Serializable key) {
        onCollectionRemove(collection, (Object) key);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCollectionUpdate(Object collection, Serializable key) {
        onCollectionUpdate(collection, (Object) key);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int[] findDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
                           String[] propertyNames, Type[] types) {
        return findDirty(entity, (Object) id, currentState, previousState, propertyNames, types);
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object getEntity(String entityName, Serializable id) {
        return getEntity(entityName, (Object) id);
    }

    @Override
    public void onInsert(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        interceptors.forEach(i -> i.onInsert(entity, id, state, propertyNames, types));
    }

    @Override
    public void onUpdate(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        interceptors.forEach(i -> i.onUpdate(entity, id, state, propertyNames, types));
    }

    @Override
    public void onUpsert(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        interceptors.forEach(i -> i.onUpsert(entity, id, state, propertyNames, types));
    }
}
