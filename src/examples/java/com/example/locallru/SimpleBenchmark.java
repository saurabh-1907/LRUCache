package com.example.locallru.examples;

import com.example.locallru.LocalLruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A simple, illustrative benchmark for {@link LocalLruCache}.
 * <p>
 * This is NOT a substitute for a proper microbenchmark using tools like JMH (Java Microbenchmark Harness).
 * Results from this benchmark should be taken as indicative only, as many factors (GC, JIT compilation,
 * warmup, etc.) are not rigorously controlled here.
 * <p>
 * The benchmark performs a mix of get and put operations concurrently across multiple threads.
 * It compares:
 * 1. {@link LocalLruCache}
 * 2. {@link java.util.concurrent.ConcurrentHashMap} (as a baseline for raw concurrent map speed, not LRU)
 * 3. A synchronized {@link LinkedHashMap} (to show why per-thread or more advanced concurrent caches are needed)
 * <p>
 * Operations:
 * - 80% GET operations
 * - 20% PUT operations
 * Keys are chosen randomly from a pre-populated set.
 */
public class SimpleBenchmark {

    private static final int NUM_THREADS = 10;
    private static final int NUM_OPERATIONS_PER_THREAD = 200_000; // Adjusted for reasonable runtime
    private static final int CACHE_CAPACITY = 1000;
    private static final int PRE_POPULATE_SIZE = CACHE_CAPACITY * 2; // Keys to choose from
    private static final double PUT_RATIO = 0.20; // 20% Puts, 80% Gets

    private static List<String> keys = new ArrayList<>(PRE_POPULATE_SIZE);

    static {
        for (int i = 0; i < PRE_POPULATE_SIZE; i++) {
            keys.add(UUID.randomUUID().toString());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Simple Benchmark (results are indicative, not for rigorous comparison)");
        System.out.printf("Threads: %d, Operations/Thread: %d, Cache Capacity: %d, Put Ratio: %.2f%n%n",
                NUM_THREADS, NUM_OPERATIONS_PER_THREAD, CACHE_CAPACITY, PUT_RATIO);

        runBenchmark("LocalLruCache", () -> {
            LocalLruCache cache = LocalLruCache.initialize(CACHE_CAPACITY, 0); // No TTL for benchmark simplicity
            return (key, value) -> {
                if (value != null) {
                    cache.addItem(key, value);
                } else {
                    cache.getItem(key);
                }
            };
        });

        runBenchmark("ConcurrentHashMap", () -> {
            Map<String, String> map = new ConcurrentHashMap<>(CACHE_CAPACITY);
            // Populate slightly to mimic cache behavior, though CHM doesn't evict
            for(int i=0; i< Math.min(PRE_POPULATE_SIZE, CACHE_CAPACITY); i++){
                map.put(keys.get(i), "value" + i);
            }
            return (key, value) -> {
                if (value != null) {
                    map.put(key, value);
                } else {
                    map.get(key);
                }
            };
        });

        runBenchmark("SynchronizedLinkedHashMap (LRU)", () -> {
            Map<String, String> map = Collections.synchronizedMap(
                new LinkedHashMap<String, String>(CACHE_CAPACITY, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > CACHE_CAPACITY;
                    }
                });
            // Pre-populate
             for(int i=0; i< Math.min(PRE_POPULATE_SIZE, CACHE_CAPACITY); i++){
                map.put(keys.get(i), "value" + i);
            }
            return (key, value) -> {
                if (value != null) {
                    map.put(key, value); // put is synchronized
                } else {
                    map.get(key);   // get is synchronized
                }
            };
        });
    }

    @FunctionalInterface
    interface CacheOperation {
        void execute(String key, String valueToPut); // if valueToPut is null, it's a get
    }

    @FunctionalInterface
    interface CacheFactory {
        CacheOperation createCache();
    }

    private static void runBenchmark(String cacheName, CacheFactory factory) throws InterruptedException {
        System.out.println("Benchmarking: " + cacheName);
        CacheOperation cacheOps = factory.createCache(); // Initialize cache instance(s)

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        Random random = new Random();

        // Warm-up phase (very basic)
        for (int i = 0; i < NUM_THREADS; i++) {
            executor.submit(() -> {
                Random threadRandom = new Random();
                for (int j = 0; j < NUM_OPERATIONS_PER_THREAD / 10; j++) { // 10% of ops for warmup
                    String key = keys.get(threadRandom.nextInt(PRE_POPULATE_SIZE));
                    if (threadRandom.nextDouble() < PUT_RATIO) {
                        cacheOps.execute(key, "warmup_value_" + threadRandom.nextInt());
                    } else {
                        cacheOps.execute(key, null);
                    }
                }
            });
        }
        // Wait for warmup tasks to complete before proceeding with actual benchmark
        // This is a simplified way; a real warmup would be more involved.
        ExecutorService warmupExecutor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch warmupLatch = new CountDownLatch(NUM_THREADS);
         for (int i = 0; i < NUM_THREADS; i++) {
            warmupExecutor.submit(() -> {
                Random threadRandom = new Random();
                for (int j = 0; j < NUM_OPERATIONS_PER_THREAD / 20; j++) { // 5% of ops for warmup
                    String key = keys.get(threadRandom.nextInt(PRE_POPULATE_SIZE));
                    if (threadRandom.nextDouble() < PUT_RATIO) {
                        cacheOps.execute(key, "warmup_value_" + threadRandom.nextInt());
                    } else {
                        cacheOps.execute(key, null);
                    }
                }
                warmupLatch.countDown();
            });
        }
        warmupExecutor.shutdown();
        warmupLatch.await(30, TimeUnit.SECONDS); // Wait for warmup to finish


        long startTime = System.nanoTime();

        for (int i = 0; i < NUM_THREADS; i++) {
            executor.submit(() -> {
                Random threadRandom = new Random(); // Each thread gets its own Random
                for (int j = 0; j < NUM_OPERATIONS_PER_THREAD; j++) {
                    String key = keys.get(threadRandom.nextInt(PRE_POPULATE_SIZE));
                    if (threadRandom.nextDouble() < PUT_RATIO) {
                        // PUT operation
                        cacheOps.execute(key, "value" + threadRandom.nextInt());
                    } else {
                        // GET operation
                        cacheOps.execute(key, null);
                    }
                }
                latch.countDown();
            });
        }

        latch.await(); // Wait for all threads to complete
        long endTime = System.nanoTime();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println(cacheName + ": Executor did not terminate in time.");
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }


        long durationNanos = endTime - startTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        long totalOperations = (long)NUM_THREADS * NUM_OPERATIONS_PER_THREAD;
        double opsPerSecond = totalOperations / durationSeconds;

        System.out.printf("  %-28s: Total Time: %.3f s, Ops/sec: %.0f%n%n", cacheName, durationSeconds, opsPerSecond);
    }
}
