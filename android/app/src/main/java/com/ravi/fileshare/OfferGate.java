package com.ravi.fileshare;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fileshare.core.Item;

/**
 * Bridges the blocking approver on the transfer thread to the user interface.
 *
 * The transfer thread parks here while you look at what is being offered; the
 * activity calls {@link #resolve} when you tap. Nothing is written to storage
 * before that decision, so declining costs nothing.
 */
public final class OfferGate {
    private OfferGate() {}

    /** Long enough to walk back to the phone, short enough that a forgotten
     *  offer does not hold the connection open indefinitely. */
    private static final long TIMEOUT_MS = 180_000;

    public static final class Pending {
        public final List<Item> items;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<boolean[]> answer = new AtomicReference<boolean[]>();

        Pending(List<Item> items) {
            this.items = items;
        }
    }

    private static final AtomicReference<Pending> CURRENT = new AtomicReference<Pending>();

    public interface Watcher {
        void onOffer(Pending p);

        void onOfferCleared();
    }

    private static volatile Watcher watcher;

    public static void setWatcher(Watcher w) {
        watcher = w;
        Pending p = CURRENT.get();
        if (w != null && p != null) w.onOffer(p);
    }

    public static Pending current() {
        return CURRENT.get();
    }

    /** Called on the transfer thread. Blocks until the user decides. */
    public static boolean[] ask(List<Item> items) {
        Pending p = new Pending(items);
        CURRENT.set(p);
        Watcher w = watcher;
        if (w != null) w.onOffer(p);

        try {
            if (!p.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.answer.set(new boolean[items.size()]); // all false
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.answer.set(new boolean[items.size()]);
        } finally {
            CURRENT.compareAndSet(p, null);
            Watcher w2 = watcher;
            if (w2 != null) w2.onOfferCleared();
        }

        boolean[] a = p.answer.get();
        return a == null ? new boolean[items.size()] : a;
    }

    /** Called from the user interface thread. */
    public static void resolve(Pending p, boolean[] decision) {
        if (p == null) return;
        p.answer.set(decision);
        p.latch.countDown();
    }

    public static void cancelAll() {
        Pending p = CURRENT.get();
        if (p != null) resolve(p, new boolean[p.items.size()]);
    }
}
