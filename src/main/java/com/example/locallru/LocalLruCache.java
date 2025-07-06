package com.example.locallru;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays; // For main method tests
import java.nio.charset.StandardCharsets; // For main method tests

/**
 * {@code LocalLruCache} provides a thread-safe, lock-free implementation of an LRU (Least Recently Used)
 * cache that utilizes {@link ThreadLocal} storage. This design ensures high performance in concurrent
 * applications by giving each thread its own independent cache instance, thereby avoiding shared locks.
 * <p>
 * Key characteristics:
 * <ul>
 *     <li><strong>Thread-Local Caches:</strong> Each thread operates on its own private cache. Data cached by one
 *         thread is not visible to, nor does it affect, other threads.</li>
 *     <li><strong>LRU Eviction:</strong> When a thread's cache reaches its capacity, the least recently used
 *         item in that specific thread's cache is evicted.</li>
 *     <li><strong>Time To Live (TTL):</strong> Cache entries can be assigned a TTL. Expired items are
 *         automatically removed upon access. TTL is based on the time of entry creation.</li>
 *     <li><strong>Configuration via {@code initialize}:</strong> Cache parameters (capacity and TTL) are
 *         set using the static {@code initialize} method. This method returns a {@code LocalLruCache}
 *         instance (handler) configured with these parameters. Threads using this handler will
 *         create their local caches with these settings upon first access.</li>
 *     <li><strong>Dynamic Configuration:</strong> Subsequent calls to {@code initialize} can create new
 *         handlers with different configurations. Threads using newer handlers will adopt the new
 *         settings for their local caches, while threads using older handlers continue with their
 *         original settings.</li>
 * </ul>
 * <p>
 * This caching strategy is beneficial for scenarios requiring high-throughput, read-heavy caching
 * where inter-thread cache coherency is not a requirement, and the memory overhead of per-thread
 * caches is acceptable. It excels in applications like web services where individual requests
 * handled by different threads can benefit from their own fast, local cache without contention.
 *
 * @see ThreadLocal
 */
public class LocalLruCache {

    /**
     * Stores the most recently set global default capacity by any call to {@link #initialize(int, long)}.
     * This value is captured by a {@code LocalLruCache} instance upon its creation.
     */
    private static volatile int lastSetCapacity = 100; // Default capacity

    /**
     * Stores the most recently set global default TTL (in milliseconds) by any call to {@link #initialize(int, long)}.
     * A value of 0 or less indicates that entries do not expire by TTL.
     * This value is captured by a {@code LocalLruCache} instance upon its creation.
     */
    private static volatile long lastSetTtlMillis = 0; // Default TTL (0 = infinite), in milliseconds

    /**
     * The capacity specific to this {@code LocalLruCache} instance (handler), captured at the time of its creation
     * via {@link #initialize(int, long)}. Each thread using this handler will get a local cache with this capacity.
     */
    private final int instanceCapacity;

    /**
     * The TTL (in milliseconds) specific to this {@code LocalLruCache} instance (handler), captured at the time of its
     * creation via {@link #initialize(int, long)}. Cache entries created by threads using this handler will use this TTL.
     * A value of 0 or less means no TTL.
     */
    private final long instanceTtlMillis;

    /**
     * Represents an entry within the {@link SimpleLruCache}. It wraps the actual cached value
     * and includes metadata such as the expiration timestamp.
     *
     * @param <V> The type of the cached value.
     */
    private static class CacheEntry<V> {
        /** The actual cached value. */
        final V value;
        /** The timestamp (in milliseconds since epoch) when this entry expires. A value of 0 or less means it never expires by TTL. */
        final long expirationTimeMillis;

        /**
         * Constructs a new cache entry.
         *
         * @param value The value to be cached.
         * @param ttlMillis The Time To Live for this entry, in milliseconds. If 0 or negative, the entry has no TTL.
         */
        CacheEntry(V value, long ttlMillis) {
            this.value = value;
            if (ttlMillis > 0) {
                this.expirationTimeMillis = System.currentTimeMillis() + ttlMillis;
            } else {
                this.expirationTimeMillis = 0; // No expiration by TTL
            }
        }

        /**
         * Checks if this cache entry has expired based on its TTL.
         *
         * @return {@code true} if the entry has expired, {@code false} otherwise (including if no TTL was set).
         */
        boolean isExpired() {
            if (expirationTimeMillis <= 0) { // No TTL set
                return false;
            }
            return System.currentTimeMillis() > expirationTimeMillis;
        }

        /**
         * Gets the cached value.
         *
         * @return The cached value.
         */
        V getValue() {
            return value;
        }
    }

    /**
     * {@code SimpleLruCache} is the core LRU cache implementation used by each thread.
     * It extends {@link LinkedHashMap} to leverage its access-order eviction policy.
     * This class is not thread-safe on its own; thread safety is provided by {@link LocalLruCache}
     * ensuring each thread gets its own instance via {@link ThreadLocal}.
     * <p>
     * It stores {@link CacheEntry} objects, which include the value and expiration metadata.
     */
    @SuppressWarnings("rawtypes") // Allows storing CacheEntry<?> which holds CacheEntry<SpecificType>
    private static class SimpleLruCache extends LinkedHashMap<String, CacheEntry> {
        private final int capacity;

        /**
         * Constructs a {@code SimpleLruCache} with a specified capacity.
         *
         * @param capacity The maximum number of entries this cache can hold. Must be positive.
         */
        SimpleLruCache(int capacity) {
            super(capacity, 0.75f, true); // true for access-order (LRU)
            this.capacity = capacity;
        }

        /**
         * Determines if the eldest entry should be removed. This is called after a put operation.
         *
         * @param eldest The eldest entry in the map.
         * @return {@code true} if the cache size exceeds its capacity, indicating the eldest entry should be removed.
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > capacity;
        }

        /**
         * Retrieves a cache entry by its key. This method is synchronized for safety, though
         * contention is minimal as each thread has its own {@code SimpleLruCache} instance.
         * Expiration checks are typically handled by the calling {@link LocalLruCache} methods.
         *
         * @param key The key of the entry to retrieve.
         * @return The {@link CacheEntry} associated with the key, or {@code null} if not found.
         */
        public synchronized CacheEntry<?> getEntry(String key) {
            return super.get(key);
        }

        /**
         * Adds or updates a cache entry. Synchronized for safety.
         *
         * @param key The key of the entry.
         * @param value The {@link CacheEntry} to store.
         */
        public synchronized void putEntry(String key, CacheEntry<?> value) {
            super.put(key, value);
        }

        /**
         * Removes a cache entry by its key. Synchronized for safety.
         *
         * @param key The key of the entry to remove.
         */
        public synchronized void removeEntry(String key) {
            super.remove(key);
        }
    }

    /**
     * The {@link ThreadLocal} variable that holds each thread's individual {@link SimpleLruCache} instance.
     * Marked as {@code transient} as {@code ThreadLocal} instances are generally not serializable.
     */
    private transient ThreadLocal<SimpleLruCache> threadLocalCache;


    /**
     * Initializes global cache parameters and returns a {@code LocalLruCache} handler instance
     * configured with these parameters.
     * <p>
     * Each thread that uses the returned handler will get a thread-local cache instance
     * initialized with the specified capacity and TTL.
     * <p>
     * For example:
     * <pre>{@code
     * LocalLruCache cacheHandler1 = LocalLruCache.initialize(100, 60); // 100 items, 60s TTL
     * LocalLruCache cacheHandler2 = LocalLruCache.initialize(50, 0);   // 50 items, no TTL
     *
     * // Threads using cacheHandler1 will have caches with capacity 100 and 60s TTL.
     * // Threads using cacheHandler2 will have caches with capacity 50 and no TTL.
     * }</pre>
     * <p>
     * Note: The {@code LocalLruCache} object returned by this method acts as a "handler" or
     * "configuration snapshot". It does not hold the cached data itself (data is in thread-local stores).
     *
     * @param capacity The maximum number of items each thread's local cache can hold. Must be positive.
     * @param ttlSeconds The Time To Live for cache entries, in seconds. A value of 0 or less
     *                   means entries do not expire based on TTL (infinite TTL).
     * @return A {@code LocalLruCache} handler instance configured with the specified parameters.
     * @throws IllegalArgumentException if capacity is not positive.
     */
    public static LocalLruCache initialize(int capacity, long ttlSeconds) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        lastSetCapacity = capacity;
        lastSetTtlMillis = (ttlSeconds > 0) ? ttlSeconds * 1000 : 0;

        // Return a new LocalLruCache instance that captures the current global settings.
        // Each thread using this specific instance will get a ThreadLocal cache
        // configured with these captured parameters.
        return new LocalLruCache(lastSetCapacity, lastSetTtlMillis);
    }

    /**
     * Private constructor for {@code LocalLruCache}. Instances are obtained via the
     * static {@link #initialize(int, long)} method.
     * This constructor captures the capacity and TTL that were globally set at the moment
     * of its invocation, storing them as instance fields. These instance-specific parameters
     * are then used to initialize the {@link ThreadLocal} cache for threads using this handler.
     *
     * @param capacity The capacity for thread-local caches created using this handler.
     * @param ttlMillis The TTL (in milliseconds) for entries in thread-local caches
     *                  created using this handler.
     */
    private LocalLruCache(int capacity, long ttlMillis) {
        this.instanceCapacity = capacity;
        this.instanceTtlMillis = ttlMillis;

        this.threadLocalCache = ThreadLocal.withInitial(() -> {
            // Each thread, upon first access through THIS LocalLruCache instance (handler),
            // will create its own SimpleLruCache using the instanceCapacity captured by this handler.
            // The instanceTtlMillis (also captured) will be used by CacheEntry when items are added.
            // Example: System.out.println(Thread.currentThread().getName() + " initializing SimpleLruCache with capacity: " + this.instanceCapacity + " and TTL: " + this.instanceTtlMillis + "ms for handler " + System.identityHashCode(this));
            return new SimpleLruCache<>(this.instanceCapacity);
        });
    }

    /**
     * Adds an item of a generic type to the current thread's local cache.
     * The item will be associated with the specified key and will adhere to the TTL
     * policy defined by this {@code LocalLruCache} handler instance.
     * If an item with the same key already exists, it will be overwritten.
     *
     * @param key The key with which the specified value is to be associated.
     * @param value The value to be associated with the specified key.
     * @param <V> The type of the value.
     */
    public <V> void addItem(String key, V value) {
        CacheEntry<V> entry = new CacheEntry<>(value, this.instanceTtlMillis);
        threadLocalCache.get().putEntry(key, entry);
    }

    /**
     * Retrieves an item of a generic type from the current thread's local cache.
     * If the item is found but has expired based on its TTL, it is removed from the
     * cache, and {@code null} is returned.
     *
     * @param key The key whose associated value is to be returned.
     * @param <V> The expected type of the value. The method will attempt to cast the
     *           retrieved value to this type.
     * @return The value to which the specified key is mapped, or {@code null} if this
     *         cache contains no mapping for the key, or if the item has expired.
     */
    @SuppressWarnings("unchecked")
    public <V> V getItem(String key) {
        CacheEntry<?> entry = threadLocalCache.get().getEntry(key);
        if (entry != null) {
            if (entry.isExpired()) {
                threadLocalCache.get().removeEntry(key); // Remove if expired
                return null;
            }
            return (V) entry.getValue(); // Type cast to V
        }
        return null;
    }

    /**
     * Adds a byte array to the current thread's local cache.
     * This is a convenience method, functionally equivalent to {@code addItem(key, value)}
     * where value is a {@code byte[]}.
     *
     * @param key The key for the byte array.
     * @param value The byte array to cache.
     */
    public void addItemBytes(String key, byte[] value) {
        addItem(key, value);
    }

    /**
     * Retrieves a byte array from the current thread's local cache.
     * This convenience method calls {@link #getItem(String)} and then checks if the
     * retrieved object is an instance of {@code byte[]}.
     *
     * @param key The key of the byte array to retrieve.
     * @return The cached byte array, or {@code null} if not found, not a {@code byte[]}, or expired.
     */
    public byte[] getItemBytes(String key) {
        Object value = getItem(key); // getItem handles expiration
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        // Optional: Log if (value != null) that type mismatch occurred
        return null;
    }

    /**
     * Adds an arbitrary object (struct) to the current thread's local cache.
     * This method is functionally equivalent to {@link #addItem(String, Object)}.
     * It stores a direct reference to the object; no serialization or deep copying is performed.
     * If the cached object is mutable, modifications to it will be reflected in the cache.
     *
     * @param key The key for the object.
     * @param value The object (struct) to cache.
     * @param <T> The type of the object.
     */
    public <T> void addStruct(String key, T value) {
        addItem(key, value);
    }

    /**
     * Retrieves an arbitrary object (struct) from the current thread's local cache.
     * This method calls {@link #getItem(String)} and then performs a runtime type check
     * against the provided {@code valueType} class.
     *
     * @param key The key of the object to retrieve.
     * @param valueType The expected {@link Class} of the object. Used for type checking and casting.
     * @param <T> The type of the object.
     * @return The cached object cast to type {@code T}, or {@code null} if not found,
     *         not of the specified {@code valueType}, or expired.
     */
    @SuppressWarnings("unchecked")
    public <T> T getStruct(String key, Class<T> valueType) {
        Object value = getItem(key); // getItem handles expiration
        if (valueType.isInstance(value)) {
            return (T) value;
        }
        // Optional: Log if (value != null) that type mismatch occurred
        return null;
    }

    /**
     * Main method for demonstration and basic testing of {@code LocalLruCache}.
     * Includes tests for LRU eviction, TTL expiration, thread-locality,
     * byte array caching, and struct caching.
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Test with initialize and basic get/set
        LocalLruCache cache1 = LocalLruCache.initialize(2, 60);
        cache1.addItem("key1", "value1_thread1");
        cache1.addItem("key2", "value2_thread1");
        System.out.println("Thread 1, cache1, key1: " + cache1.getItem("key1")); // value1_thread1
        cache1.addItem("key3", "value3_thread1"); // key1 should be evicted
        System.out.println("Thread 1, cache1, key1 after key3 add: " + cache1.getItem("key1")); // null
        System.out.println("Thread 1, cache1, key2: " + cache1.getItem("key2")); // value2_thread1
        System.out.println("Thread 1, cache1, key3: " + cache1.getItem("key3")); // value3_thread1

        // Test that initialize creates a new "context" for parameters if called again
        LocalLruCache cache2 = LocalLruCache.initialize(1, 30); // Smaller capacity
        cache2.addItem("keyA", "valueA_thread1_cache2");
        System.out.println("Thread 1, cache2, keyA: " + cache2.getItem("keyA"));
        cache2.addItem("keyB", "valueB_thread1_cache2"); // keyA should be evicted from cache2's context
        System.out.println("Thread 1, cache2, keyA after keyB add: " + cache2.getItem("keyA")); // null
        System.out.println("Thread 1, cache2, keyB: " + cache2.getItem("keyB"));

        // Verify cache1 is not affected by cache2's re-initialization on the same thread
        System.out.println("Thread 1, cache1, key3 (checking independence): " + cache1.getItem("key3")); // value3_thread1

        // Test thread locality
        LocalLruCache sharedCacheConfig = LocalLruCache.initialize(3, 10); // Config for multiple threads

        Thread t1 = new Thread(() -> {
            sharedCacheConfig.addItem("t_key1", "val_t1");
            sharedCacheConfig.addItem("t_key2", "val_t1");
            System.out.println("Thread T1, sharedCacheConfig, t_key1: " + sharedCacheConfig.getItem("t_key1"));
            System.out.println("Thread T1, sharedCacheConfig, common_key: " + sharedCacheConfig.getItem("common_key")); // Expect null initially
            sharedCacheConfig.addItem("common_key", "val_from_T1");
            System.out.println("Thread T1, sharedCacheConfig, common_key after add: " + sharedCacheConfig.getItem("common_key"));
        }, "T1");

        Thread t2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {} // Ensure t1 runs first slightly
            System.out.println("Thread T2, sharedCacheConfig, t_key1: " + sharedCacheConfig.getItem("t_key1")); // Expect null (different thread)
            System.out.println("Thread T2, sharedCacheConfig, common_key: " + sharedCacheConfig.getItem("common_key")); // Expect null
            sharedCacheConfig.addItem("common_key", "val_from_T2");
            System.out.println("Thread T2, sharedCacheConfig, common_key after add: " + sharedCacheConfig.getItem("common_key"));
        }, "T2");

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Main thread, sharedCacheConfig, common_key: " + sharedCacheConfig.getItem("common_key")); // null, main thread has its own cache
        sharedCacheConfig.addItem("main_common", "val_from_main");
        System.out.println("Main thread, sharedCacheConfig, main_common after add: " + sharedCacheConfig.getItem("main_common"));


        // Test struct caching (still uses generic addItem/getItem)
        MyStruct struct = new MyStruct("TestStruct", 100);
        sharedCacheConfig.addStruct("myAppStruct", struct);
        MyStruct retrieved = sharedCacheConfig.getStruct("myAppStruct", MyStruct.class);
        System.out.println("Main thread, sharedCacheConfig, retrieved struct: " + retrieved);
        System.out.println("Structs equal (value equality)? " + struct.equals(retrieved));
        System.out.println("Structs same instance (reference equality)? " + (struct == retrieved));

        assert struct.equals(retrieved) : "Structs should be equal by value";
        assert struct == retrieved : "Structs should be the same instance (direct reference caching)";


        // Test TTL
        System.out.println("\n--- TTL Test ---");
        LocalLruCache ttlCache = LocalLruCache.initialize(5, 1); // 1 second TTL
        ttlCache.addItem("ttl_key1", "survives_short_wait");
        ttlCache.addItem("ttl_key2", "expires_after_1s");
        System.out.println("Before sleep: ttl_key1 = " + ttlCache.getItem("ttl_key1"));
        System.out.println("Before sleep: ttl_key2 = " + ttlCache.getItem("ttl_key2"));

        try {
            System.out.println("Sleeping for 1.5 seconds...");
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("After sleep: ttl_key1 (was re-accessed, so TTL might be from last access if LRU updates timestamp - current impl. is creation time TTL): " + ttlCache.getItem("ttl_key1"));
        System.out.println("After sleep: ttl_key2 = " + ttlCache.getItem("ttl_key2")); // Should be null

        ttlCache.addItem("ttl_key3", "new_item_after_sleep");
        System.out.println("After sleep, new item: ttl_key3 = " + ttlCache.getItem("ttl_key3"));

        // Test byte array caching
        System.out.println("\n--- Byte Array Test ---");
        LocalLruCache byteCache = LocalLruCache.initialize(3, 0); // No TTL for this test
        byte[] data1 = "Hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] data2 = new byte[]{10, 20, 30, 40, 50};

        byteCache.addItemBytes("bytesKey1", data1);
        byteCache.addItem("bytesKey2", data2); // Test with generic add too

        byte[] retrievedBytes1 = byteCache.getItemBytes("bytesKey1");
        byte[] retrievedBytes2 = (byte[]) byteCache.getItem("bytesKey2"); // Test with generic get
        byte[] retrievedBytesNonExistent = byteCache.getItemBytes("nonExistentBytesKey");

        System.out.println("Retrieved bytesKey1: " + (retrievedBytes1 != null ? new String(retrievedBytes1, java.nio.charset.StandardCharsets.UTF_8) : "null"));
        System.out.println("Retrieved bytesKey2: " + (retrievedBytes2 != null ? java.util.Arrays.toString(retrievedBytes2) : "null"));
        System.out.println("Retrieved nonExistentBytesKey: " + (retrievedBytesNonExistent != null ? "Exists" : "null"));

        assert java.util.Arrays.equals(data1, retrievedBytes1) : "Byte array data1 mismatch";
        assert java.util.Arrays.equals(data2, retrievedBytes2) : "Byte array data2 mismatch";
        assert retrievedBytesNonExistent == null : "Non-existent byte array key should return null";

        // Test type safety of getItemBytes
        byteCache.addItem("stringKeyForByteTest", "This is a string, not bytes");
        byte[] notBytes = byteCache.getItemBytes("stringKeyForByteTest");
        System.out.println("getItemBytes for a String item: " + (notBytes == null ? "null (correctly)" : "Error: should be null"));
        assert notBytes == null : "getItemBytes should return null if item is not a byte[]";

    }

    // Example struct for testing
    static class MyStruct {
        String field1;
        int field2;

        public MyStruct(String field1, int field2) {
            this.field1 = field1;
            this.field2 = field2;
        }

        @Override
        public String toString() {
            return "MyStruct{field1='" + field1 + "', field2=" + field2 + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MyStruct myStruct = (MyStruct) o;
            return field2 == myStruct.field2 && java.util.Objects.equals(field1, myStruct.field1);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(field1, field2);
        }
    }
}
