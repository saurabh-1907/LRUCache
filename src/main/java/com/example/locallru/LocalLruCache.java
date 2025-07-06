package com.example.locallru;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays; // For main method tests
import java.nio.charset.StandardCharsets; // For main method tests

/**
 * A thread-safe, lock-free LRU (Least Recently Used) cache using {@link ThreadLocal} storage.
 * <p>
 * Each thread gets its own cache, avoiding locks for high performance. Key features:
 * <ul>
 *     <li><b>Thread-Local:</b> Caches are per-thread; data isn't shared.
 *     <li><b>LRU Eviction:</b> Removes the least recently used item when a thread's cache is full.
 *     <li><b>TTL Support:</b> Entries can expire based on a Time To Live.
 *     <li><b>Configurable:</b> Use {@link #initialize(int, long)} to get a cache handler with specific
 *         capacity and TTL. Different handlers can have different settings.
 * </ul>
 * Suitable for high-throughput, read-heavy scenarios where per-thread caches are acceptable
 * (e.g., web services caching per-request data).
 *
 * @see ThreadLocal
 */
public class LocalLruCache {

    // Global defaults, updated by initialize() and captured by LocalLruCache handler instances.
    private static volatile int globalDefaultCapacity = 100;
    private static volatile long globalDefaultTtlMillis = 0; // 0 or less means entries don't expire by TTL.

    /** Capacity for this specific cache handler instance. */
    private final int instanceCapacity;

    /** TTL in milliseconds for this specific cache handler instance. 0 or less means no TTL. */
    private final long instanceTtlMillis;

    /**
     * An entry in the cache, storing the value and its expiration time.
     * @param <V> Value type.
     */
    private static class CacheEntry<V> {
        final V value;
        final long expirationTimeMillis; // 0 or less means no TTL

        /**
         * Creates a cache entry.
         * @param value Value to cache.
         * @param ttlMillis TTL in milliseconds (0 or less for no TTL).
         */
        CacheEntry(V value, long ttlMillis) {
            this.value = value;
            this.expirationTimeMillis = (ttlMillis > 0) ? System.currentTimeMillis() + ttlMillis : 0;
        }

        /**
         * Checks if expired.
         * @return True if expired, false otherwise (including if no TTL).
         */
        boolean isExpired() {
            return expirationTimeMillis > 0 && System.currentTimeMillis() > expirationTimeMillis;
        }

        V getValue() {
            return value;
        }
    }

    /**
     * Core LRU cache for each thread, extending {@link LinkedHashMap}.
     * Not thread-safe on its own; {@link LocalLruCache} ensures per-thread instances.
     * Stores {@link CacheEntry} objects.
     */
    @SuppressWarnings("rawtypes") // Suppress warning for using raw CacheEntry type in LinkedHashMap
    private static class SimpleLruCache extends LinkedHashMap<String, CacheEntry> {
        private final int capacity;

        /**
         * Creates a SimpleLruCache.
         * @param capacity Max entries. Must be positive.
         */
        SimpleLruCache(int capacity) {
            // true for access-order, which is essential for LRU behavior
            super(capacity, 0.75f, true);
            this.capacity = capacity;
        }

        /**
         * Called after put: remove eldest if size > capacity.
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > capacity;
        }

        // These methods operate on the LinkedHashMap instance. Since each thread has its
        // own SimpleLruCache (and thus its own LinkedHashMap) via ThreadLocal,
        // external synchronization is not needed for thread safety here.
        // LinkedHashMap itself is not thread-safe if shared, but it's not shared across threads here.
        public CacheEntry<?> getEntry(String key) {
            return super.get(key);
        }

        public void putEntry(String key, CacheEntry<?> value) {
            super.put(key, value);
        }

        public void removeEntry(String key) {
            super.remove(key);
        }
    }

    /** Holds each thread's {@link SimpleLruCache}. Transient as ThreadLocal isn't typically serializable. */
    private transient ThreadLocal<SimpleLruCache> threadLocalCache;


    /**
     * Creates a {@code LocalLruCache} handler with specified global defaults for capacity and TTL.
     * <p>
     * Threads using this handler get a local cache with these settings.
     * Example:
     * <pre>{@code
     * LocalLruCache handler1 = LocalLruCache.initialize(100, 60); // 100 items, 60s TTL
     * LocalLruCache handler2 = LocalLruCache.initialize(50, 0);   // 50 items, no TTL
     * }</pre>
     * This handler is a configuration snapshot; data is in thread-local stores.
     *
     * @param capacity Max items per thread's cache (must be positive).
     * @param ttlSeconds TTL for entries in seconds (0 or less for infinite TTL).
     * @return A configured {@code LocalLruCache} handler.
     * @throws IllegalArgumentException if capacity is not positive.
     */
    public static LocalLruCache initialize(int capacity, long ttlSeconds) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        // Update global defaults; new LocalLruCache handlers will use these.
        globalDefaultCapacity = capacity;
        globalDefaultTtlMillis = (ttlSeconds > 0) ? ttlSeconds * 1000 : 0;

        // Return a new handler instance that captures the current global settings.
        return new LocalLruCache(globalDefaultCapacity, globalDefaultTtlMillis);
    }

    /**
     * Private constructor. Use {@link #initialize(int, long)}.
     * Captures global capacity/TTL at its creation for this handler instance.
     * These are then used for this handler's {@link ThreadLocal} caches.
     *
     * @param capacity Capacity for this handler's thread-local caches.
     * @param ttlMillis TTL (ms) for this handler's thread-local caches.
     */
    private LocalLruCache(int capacity, long ttlMillis) {
        this.instanceCapacity = capacity;
        this.instanceTtlMillis = ttlMillis;

        // Each thread using THIS handler instance gets its own SimpleLruCache,
        // configured with this handler's captured capacity and TTL settings.
        this.threadLocalCache = ThreadLocal.withInitial(() -> new SimpleLruCache(this.instanceCapacity));
    }

    /**
     * Adds an item to the current thread's local cache.
     * Uses the TTL policy of this {@code LocalLruCache} handler.
     * Overwrites existing items with the same key.
     * <p>
     * This generic method can be used for any type, including byte arrays or custom objects.
     * For example:
     * <pre>{@code
     * cache.addItem("myBytes", new byte[]{1, 2, 3});
     * cache.addItem("myObject", new MyCustomObject());
     * }</pre>
     *
     * @param key Item's key.
     * @param value Item's value.
     * @param <V> Value type.
     */
    public <V> void addItem(String key, V value) {
        // The instanceTtlMillis for this specific handler is used when creating the CacheEntry.
        CacheEntry<V> entry = new CacheEntry<>(value, this.instanceTtlMillis);
        threadLocalCache.get().putEntry(key, entry);
    }

    /**
     * Retrieves an item from the current thread's local cache.
     * Returns null if not found or if the item has expired (and removes it).
     * <p>
     * The caller is responsible for casting the returned value to the expected type.
     * For example:
     * <pre>{@code
     * String strVal = cache.getItem("stringKey"); // Implicit cast may work for Object to String
     * byte[] bytesVal = (byte[]) cache.getItem("byteKey");
     * MyCustomObject objVal = (MyCustomObject) cache.getItem("objectKey");
     * }</pre>
     *
     * @param key Item's key.
     * @param <V> Expected value type (casting is attempted by the JVM on assignment).
     * @return The value, or null if not found/expired.
     */
    @SuppressWarnings("unchecked")
    public <V> V getItem(String key) {
        CacheEntry<?> entry = threadLocalCache.get().getEntry(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            threadLocalCache.get().removeEntry(key); // Eagerly remove expired entry upon access
            return null;
        }
        // Caller is responsible for knowing the type and casting appropriately.
        return (V) entry.getValue();
    }

    // Note: Specific helper methods like addItemBytes, getItemBytes, addStruct, getStruct
    // were removed in favor of using the generic addItem/getItem and type casting by the caller.

    /**
     * Main method for demonstration and basic tests.
     * Covers LRU, TTL, thread-locality, byte arrays, and structs.
     * @param args Not used.
     */
    public static void main(String[] args) {
        System.out.println("--- Basic LRU Test (Capacity 2, TTL 60s) ---");
        LocalLruCache cache1 = LocalLruCache.initialize(2, 60); // Handler 1: cap 2, 60s TTL
        cache1.addItem("key1", "value1");
        cache1.addItem("key2", "value2");
        System.out.println("cache1.get('key1'): " + cache1.getItem("key1"));
        cache1.addItem("key3", "value3"); // "key1" should be evicted
        System.out.println("cache1.get('key1') after 'key3' added: " + cache1.getItem("key1"));
        System.out.println("cache1.get('key2'): " + cache1.getItem("key2"));
        System.out.println("cache1.get('key3'): " + cache1.getItem("key3"));

        System.out.println("\n--- Handler Independence Test (Capacity 1, TTL 30s) ---");
        LocalLruCache cache2 = LocalLruCache.initialize(1, 30); // Handler 2: cap 1, 30s TTL
        cache2.addItem("keyA", "valueA_handler2");
        System.out.println("cache2.get('keyA'): " + cache2.getItem("keyA"));
        cache2.addItem("keyB", "valueB_handler2"); // "keyA" should be evicted from this handler's cache context
        System.out.println("cache2.get('keyA') after 'keyB' added: " + cache2.getItem("keyA"));
        System.out.println("cache2.get('keyB'): " + cache2.getItem("keyB"));
        // Verify cache1 (using Handler 1) is not affected
        System.out.println("cache1.get('key3') (unaffected by cache2): " + cache1.getItem("key3"));

        System.out.println("\n--- Thread Locality Test (Capacity 3, TTL 10s) ---");
        LocalLruCache sharedHandler = LocalLruCache.initialize(3, 10); // Handler used by multiple threads

        Thread t1 = new Thread(() -> {
            sharedHandler.addItem("t_key1", "val_t1_thread_A");
            sharedHandler.addItem("t_key2", "val_t2_thread_A");
            System.out.println(Thread.currentThread().getName() + " get('t_key1'): " + sharedHandler.getItem("t_key1"));
            System.out.println(Thread.currentThread().getName() + " get('common_key') (before add): " + sharedHandler.getItem("common_key"));
            sharedHandler.addItem("common_key", "val_from_Thread-A");
            System.out.println(Thread.currentThread().getName() + " get('common_key') (after add): " + sharedHandler.getItem("common_key"));
        }, "Thread-A");

        Thread t2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // Brief pause for demo
            // These items are from Thread-A's cache, so they won't be found in Thread-B's cache.
            System.out.println(Thread.currentThread().getName() + " get('t_key1'): " + sharedHandler.getItem("t_key1"));
            System.out.println(Thread.currentThread().getName() + " get('common_key') (before add): " + sharedHandler.getItem("common_key"));
            sharedHandler.addItem("common_key", "val_from_Thread-B");
            System.out.println(Thread.currentThread().getName() + " get('common_key') (after add): " + sharedHandler.getItem("common_key"));
        }, "Thread-B");

        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        // Main thread also has its own cache instance when using sharedHandler
        System.out.println(Thread.currentThread().getName() + " get('common_key'): " + sharedHandler.getItem("common_key"));
        sharedHandler.addItem("main_common", "val_from_main_thread");
        System.out.println(Thread.currentThread().getName() + " get('main_common'): " + sharedHandler.getItem("main_common"));

        System.out.println("\n--- Struct Caching Test (using generic methods) ---");
        MyStruct myData = new MyStruct("ExampleStruct", 123);
        sharedHandler.addItem("structKey", myData); // Using generic addItem
        MyStruct retrievedData = (MyStruct) sharedHandler.getItem("structKey"); // Using generic getItem with cast
        System.out.println("Retrieved struct: " + retrievedData);
        System.out.println("Structs equal (value): " + (retrievedData != null && myData.equals(retrievedData)));
        System.out.println("Structs same instance (ref): " + (myData == retrievedData));
        assert retrievedData != null && myData.equals(retrievedData) : "Structs should be equal by value.";
        assert myData == retrievedData : "Structs should be same instance (direct reference).";

        System.out.println("\n--- TTL Expiration Test (TTL 1s) ---");
        LocalLruCache ttlCache = LocalLruCache.initialize(5, 1); // Handler with 1-second TTL
        ttlCache.addItem("ttl_item1", "short_lived_val");
        ttlCache.addItem("ttl_item2", "will_expire_val");
        System.out.println("Before sleep: ttl_item1 = " + ttlCache.getItem("ttl_item1"));
        System.out.println("Before sleep: ttl_item2 = " + ttlCache.getItem("ttl_item2"));

        try {
            System.out.println("Sleeping for ~1.5 seconds for TTL expiration...");
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        // Items should have expired and be removed on access
        System.out.println("After sleep: ttl_item1 = " + ttlCache.getItem("ttl_item1"));
        System.out.println("After sleep: ttl_item2 = " + ttlCache.getItem("ttl_item2"));

        ttlCache.addItem("ttl_item3", "new_item_post_sleep");
        System.out.println("After sleep, new item: ttl_item3 = " + ttlCache.getItem("ttl_item3"));

        System.out.println("\n--- Byte Array Caching Test (No TTL) ---");
        LocalLruCache byteCacheHandler = LocalLruCache.initialize(3, 0); // Handler with no TTL for this test
        byte[] bytes1 = "TestBytes".getBytes(StandardCharsets.UTF_8);
        byte[] bytes2 = {1, 2, 3, 4, 5};

        byteCacheHandler.addItem("keyBytes1", bytes1);
        byteCacheHandler.addItem("keyBytes2", bytes2);

        byte[] retrievedB1 = (byte[]) byteCacheHandler.getItem("keyBytes1");
        byte[] retrievedB2 = (byte[]) byteCacheHandler.getItem("keyBytes2");
        byte[] nonExistentBytes = (byte[]) byteCacheHandler.getItem("noSuchBytesKey");

        System.out.println("Retrieved keyBytes1: " + (retrievedB1 != null ? new String(retrievedB1, StandardCharsets.UTF_8) : "null"));
        System.out.println("Retrieved keyBytes2: " + (retrievedB2 != null ? Arrays.toString(retrievedB2) : "null"));
        System.out.println("Retrieved noSuchBytesKey: " + (nonExistentBytes != null ? "ERROR (should be null)" : "null (correct)"));

        assert retrievedB1 != null && Arrays.equals(bytes1, retrievedB1) : "Byte array 'bytes1' mismatch.";
        assert retrievedB2 != null && Arrays.equals(bytes2, retrievedB2) : "Byte array 'bytes2' mismatch.";
        assert nonExistentBytes == null : "Non-existent byte array key should yield null.";

        byteCacheHandler.addItem("stringKeyAsBytesTest", "This is a String.");
        Object stringValue = byteCacheHandler.getItem("stringKeyAsBytesTest");
        System.out.println("getItem for 'stringKeyAsBytesTest' (retrieved as Object): " + stringValue);
        assert "This is a String.".equals(stringValue) : "String value mismatch for 'stringKeyAsBytesTest'.";

        // Test ClassCastException when incorrectly casting a String to byte[]
        try {
            byte[] notActuallyBytes = (byte[]) byteCacheHandler.getItem("stringKeyAsBytesTest");
            System.out.println("Casting String to byte[] did not throw CCE (unexpected): " + Arrays.toString(notActuallyBytes));
            assert false : "Should have thrown ClassCastException when casting String to byte[].";
        } catch (ClassCastException e) {
            System.out.println("Casting String to byte[] correctly threw ClassCastException.");
        }

        System.out.println("\nAll basic tests in main completed.");
    }

    // Example struct for testing caching of arbitrary objects
    static class MyStruct {
        String name;
        int value;

        public MyStruct(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return "MyStruct{name='" + name + "', value=" + value + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MyStruct myStruct = (MyStruct) o;
            return value == myStruct.value && Objects.equals(name, myStruct.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }
}
