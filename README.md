
# Java Thread-Local LRU Cache

`LocalLruCache` is a Java library providing a simple, thread-safe, and lock-free LRU (Least Recently Used) cache implementation. It leverages `ThreadLocal` storage to achieve high performance in concurrent applications by giving each thread its own independent cache instance, thus avoiding shared locks and contention.

## Features

*   **Thread-Safe and Lock-Free:** Each thread operates on its own private cache instance. Data cached by one thread is not visible to, nor does it affect, other threads.
*   **LRU Eviction Policy:** When a thread's cache reaches its configured capacity, the least recently used item in that specific thread's cache is evicted.
*   **Time To Live (TTL):** Cache entries can be assigned a TTL. Expired items are automatically removed upon access (or not returned). TTL is based on the time of entry creation.
*   **Configurable Cache Handlers:**
    *   Cache parameters (capacity and TTL) are set using the static `LocalLruCache.initialize(int capacity, long ttlSeconds)` method.
    *   This method returns a `LocalLruCache` instance (referred to as a "handler") configured with these parameters.
    *   Threads using a specific handler will create their local caches with these settings upon first access (`addItem` or `getItem`).
    *   Different handlers can be created with different configurations, allowing various parts of an application (or different threads) to use caches with distinct behaviors if needed.

## Use Cases

This caching strategy is beneficial for scenarios requiring high-throughput, read-heavy caching where:
*   Inter-thread cache coherency is not a strict requirement (i.e., it's acceptable for different threads to fetch/recompute the same data initially).
*   The memory overhead of per-thread caches is acceptable.
It can excel in applications like web services where individual requests, often handled by different threads, can benefit from their own fast, local cache without contention.

## Basic Usage

### Initialization

To get a cache handler, use the static `initialize` method:

```java
// Get a cache handler configured for a capacity of 100 items and a TTL of 60 seconds
LocalLruCache cacheHandler = LocalLruCache.initialize(100, 60);

// Get another handler, perhaps for a different use case, with different settings
LocalLruCache smallShortLivedCacheHandler = LocalLruCache.initialize(10, 5); // 10 items, 5s TTL

// A handler for cache with infinite TTL (ttlSeconds <= 0)
LocalLruCache infiniteTtlCacheHandler = LocalLruCache.initialize(200, 0);
```
The `LocalLruCache` object returned by `initialize` acts as a "handler" or "configuration snapshot". It doesn't hold the cached data itself; data is stored in the thread-local stores that are created when a thread first uses the handler.

### Adding Items

Use the `addItem` method on a handler instance. The item will be added to the current thread's local cache associated with that handler.

```java
// Using the first handler
cacheHandler.addItem("myKey1", "myValue1");

// Assuming MyCustomObject is a class you've defined
// MyCustomObject customObject = new MyCustomObject("data");
// cacheHandler.addItem("myObjectKey", customObject);

byte[] fileBytes = new byte[]{0, 1, 2, 3};
cacheHandler.addItem("myFileBytesKey", fileBytes);
```

If an item with the same key already exists in the current thread's cache for that handler, it will be overwritten.

### Retrieving Items

Use the `getItem` method. It retrieves from the current thread's local cache associated with the handler. You need to cast the result to the expected type.

```java
// Retrieve a String
String value1 = (String) cacheHandler.getItem("myKey1");
if (value1 != null) {
    System.out.println("Retrieved: " + value1);
} else {
    System.out.println("Item not found or expired.");
}

// Retrieve a custom object
// Assuming MyCustomObject is a class you've defined
// MyCustomObject retrievedObject = (MyCustomObject) cacheHandler.getItem("myObjectKey");
// if (retrievedObject != null) {
//     // Use the object
// }

// Retrieve a byte array
byte[] retrievedBytes = (byte[]) cacheHandler.getItem("myFileBytesKey");
if (retrievedBytes != null) {
    // Process the bytes
}
```

If an item is not found, or if it was found but has expired based on its TTL, `getItem` will return `null`. (Expired items are also removed from the cache upon such an access).

### Example: Working with Multiple Threads

```java
LocalLruCache userCacheConfig = LocalLruCache.initialize(50, 300); // 50 items, 5 mins TTL

// Placeholder for a UserPreferences class, replace with your actual class
class UserPreferences {
    String data;
    public UserPreferences(String data) { this.data = data; }
    @Override public String toString() { return "UserPreferences{data='" + data + "'}"; }
}

// Placeholder for a method that would fetch data, e.g., from a database
UserPreferences fetchUserPreferencesFromDb(String userId) {
    System.out.println("Fetching preferences for " + userId + " from DB (simulated)");
    return new UserPreferences("Data for " + userId);
}

// Thread 1
new Thread(() -> {
    String userId = "user123";
    UserPreferences prefs = fetchUserPreferencesFromDb(userId); // some method to get data
    if (prefs != null) {
        userCacheConfig.addItem("prefs_" + userId, prefs);
    }
    // ... later
    UserPreferences cachedPrefs = (UserPreferences) userCacheConfig.getItem("prefs_" + userId);
    if (cachedPrefs != null) {
        System.out.println("Thread 1 using cached prefs for " + userId + ": " + cachedPrefs);
    }
},"Thread-1-Worker").start();

// Thread 2
new Thread(() -> {
    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // Stagger threads slightly for demo
    String userId = "user456";
     // This thread will have its own cache for userCacheConfig.
     // It won't see "prefs_user123" unless it adds it itself.
    UserPreferences prefsOther = (UserPreferences) userCacheConfig.getItem("prefs_" + userId);
    if (prefsOther == null) {
        prefsOther = fetchUserPreferencesFromDb(userId);
        if (prefsOther != null) {
            userCacheConfig.addItem("prefs_" + userId, prefsOther);
            System.out.println("Thread 2 fetched and cached prefs for " + userId + ": " + prefsOther);
        }
    } else {
        System.out.println("Thread 2 found cached prefs for " + userId + ": " + prefsOther);
    }
},"Thread-2-Worker").start();
```

In this example, each thread maintains its own cache of user preferences. An item cached by Thread 1 (e.g., for `user123`) is not accessible to Thread 2, unless Thread 2 also happens to cache an item with the exact same key.

## Building

This project can be compiled as a standard Java library. No external dependencies beyond standard Java are required for the cache logic itself.
To compile the `LocalLruCache.java` file (e.g., if it's in `src/com/example/locallru/LocalLruCache.java` from a project root):
```sh
javac src/com/example/locallru/LocalLruCache.java
```
To run the main method for demonstration (assuming you are in the project root and the file compiled to `src/com/example/locallru/LocalLruCache.class` or similar relative to a classpath root):
```sh
java -cp src com.example.locallru.LocalLruCache
```
For larger projects, using a build tool like Maven or Gradle is recommended.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues if you find any bugs or have suggestions for improvements.
```
