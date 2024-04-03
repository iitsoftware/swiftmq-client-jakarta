package com.swiftmq.tools.gc;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

public class WeakPool<T> {
    private final ConcurrentLinkedQueue<WeakReference<T>> pool = new ConcurrentLinkedQueue<>();
    private final ReferenceQueue<T> refQueue = new ReferenceQueue<>();

    public T get(Supplier<T> creator) {
        cleanUp();

        WeakReference<T> ref;
        T object;

        // Look for an existing object
        while ((ref = pool.poll()) != null) {
            object = ref.get();
            if (object != null) {
                return object;
            }
        }

        // No objects in the pool, create a new one
        return creator.get();
    }

    public void checkIn(T object) {
        pool.offer(new WeakReference<>(object, refQueue));
    }

    private void cleanUp() {
        WeakReference<T> ref;
        while ((ref = (WeakReference<T>) refQueue.poll()) != null) {
            pool.remove(ref);
        }
    }
}
