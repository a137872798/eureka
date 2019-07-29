package com.netflix.discovery.util;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An alternative to {@link String#intern()} with no capacity constraints.
 *
 * 针对 string 的缓存对象 实际上是通过 String.intern() 设置到常量池
 * @author Tomasz Bak
 */
public class StringCache {

    public static final int LENGTH_LIMIT = 38;

    private static final StringCache INSTANCE = new StringCache();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, WeakReference<String>> cache = new WeakHashMap<String, WeakReference<String>>();
    private final int lengthLimit;

    public StringCache() {
        this(LENGTH_LIMIT);
    }

    public StringCache(int lengthLimit) {
        this.lengthLimit = lengthLimit;
    }

    /**
     * 尝试从缓存中获取
     * @param str
     * @return
     */
    public String cachedValueOf(final String str) {
        if (str != null && (lengthLimit < 0 || str.length() <= lengthLimit)) {
            // Return value from cache if available
            try {
                // 使用读锁
                lock.readLock().lock();
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
            } finally {
                lock.readLock().unlock();
            }

            // 读锁没有获取到的情况 使用写锁
            // Update cache with new content
            try {
                lock.writeLock().lock();
                // 这段代码应该是为了让 一个方法中的锁能够避免与未来可能生成的锁产生竞争 而做了完备的校验
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
                // 将数据保存到缓存中
                cache.put(str, new WeakReference<>(str));
            } finally {
                lock.writeLock().unlock();
            }
            return str;
        }
        return str;
    }

    public int size() {
        try {
            lock.readLock().lock();
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public static String intern(String original) {
        return INSTANCE.cachedValueOf(original);
    }
}
