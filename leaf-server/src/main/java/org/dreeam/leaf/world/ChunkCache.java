package org.dreeam.leaf.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;

public final class ChunkCache<V> extends Long2ReferenceOpenHashMap<V> {
    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private long k1 = EMPTY_KEY;
    private V v1 = null;
    private Thread thread;

    public ChunkCache(Thread thread, final int expected, final float f) {
        super(expected, f);
        this.thread = thread;
    }

    public V get(long k) {
        long k1 = this.k1;
        V v1 = this.v1;
        if (k1 == k && v1 != null) {
            return v1;
        }
        if (k == 0L) {
            if (this.containsNullKey) {
                this.k1 = k;
                return this.v1 = this.value[this.n];
            } else {
                return null;
            }
        } else {
            long curr;
            final long[] key = this.key;
            int pos;
            if ((curr = key[pos = (int) HashCommon.mix(k) & this.mask]) == 0) {
                return null;
            } else if (k == curr) {
                this.k1 = k;
                return this.v1 = this.value[pos];
            } else {
                while (true) {
                    if ((curr = key[pos = pos + 1 & this.mask]) == 0) {
                        return null;
                    }
                    if (k == curr) {
                        this.k1 = k;
                        return this.v1 = this.value[pos];
                    }
                }
            }
        }
    }

    @Override
    public V remove(long k) {
        if (k == k1) {
            v1 = null;
            k1 = EMPTY_KEY;
        }
        return super.remove(k);
    }

    @Override
    public V put(long k, V levelChunk) {
        if (k == k1) {
            v1 = null;
            k1 = EMPTY_KEY;
        }
        return super.put(k, levelChunk);
    }

    public void clear() {
        v1 = null;
        k1 = EMPTY_KEY;
        super.clear();
    }

    public void setThread() {
        this.thread = Thread.currentThread();
    }

    public boolean isSameThread() {
        return Thread.currentThread() == this.thread;
    }

    public void ensureSameThread() {
        if (Thread.currentThread() != this.thread) {
            throw new IllegalStateException("Thread failed main thread check: Cannot update chunk status asynchronously, context=thread=" + Thread.currentThread().getName());
        }
    }

    @Override
    public V defaultReturnValue() {
        return null;
    }

    @Override
    public void defaultReturnValue(V rv) {
        throw new UnsupportedOperationException();
    }
}
