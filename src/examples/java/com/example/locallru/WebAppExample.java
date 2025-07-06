package com.example.locallru.examples;

import com.example.locallru.LocalLruCache;
import static spark.Spark.*; // Imports static methods like get(), post(), port()

import com.google.gson.Gson; // For simple JSON request/response parsing

import java.util.HashMap;
import java.util.Map;

/**
 * Demonstrates the use of {@link LocalLruCache} in a simple web application context
 * using the SparkJava framework.
 * <p>
 * To run this example:
 * 1. Make sure you have SparkJava and a JSON library (like Gson) in your classpath.
 *    For Maven, you would add dependencies like:
 *    <pre>{@code
 *    <dependency>
 *        <groupId>com.sparkjava</groupId>
 *        <artifactId>spark-core</artifactId>
 *        <version>2.9.4</version> <!-- Or the latest version -->
 *    </dependency>
 *    <dependency>
 *        <groupId>com.google.code.gson</groupId>
 *        <artifactId>gson</artifactId>
 *        <version>2.10.1</version> <!-- Or the latest version -->
 *    </dependency>
 *    }</pre>
 * 2. Compile and run this class.
 * 3. Use a tool like curl or Postman to interact with the endpoints:
 *    - POST to /cache: Send a JSON body like {"key": "myKey", "value": "myValue"}
 *      Response: {"status":"success", "key":"myKey", "value":"myValue", "thread": "ThreadName"}
 *    - GET from /cache/{key}: e.g., /cache/myKey
 *      Response (if found in current thread's cache): {"status":"found", "key":"myKey", "value":"myValue", "thread": "ThreadName"}
 *      Response (if not found): {"status":"not_found", "key":"myKey", "thread": "ThreadName"}
 * <p>
 * This example highlights that each request (potentially handled by a different thread from Spark's pool)
 * will interact with its own thread-local cache. An item cached via a POST request by one thread
 * will only be retrievable by a GET request if that GET request happens to be processed by the *same thread*.
 * This demonstrates the core nature of LocalLruCache.
 */
public class WebAppExample {

    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        // Initialize the LocalLruCache.
        // All threads in this Spark application will use this configuration
        // when they first access their thread-local cache.
        // Capacity: 100 items per thread, TTL: 120 seconds
        LocalLruCache cache = LocalLruCache.initialize(100, 120);

        port(4567); // SparkJava default port

        System.out.println("WebAppExample running on port 4567");
        System.out.println("Try: POST /cache with JSON {\"key\":\"someKey\", \"value\":\"someValue\"}");
        System.out.println("Then: GET /cache/someKey");
        System.out.println("Observe thread names to see thread-local behavior.");

        // Endpoint to add an item to the cache
        post("/cache", (request, response) -> {
            response.type("application/json");
            String requestBody = request.body();
            CacheItem item = gson.fromJson(requestBody, CacheItem.class);

            if (item == null || item.key == null || item.value == null) {
                response.status(400); // Bad Request
                return gson.toJson(new StatusResponse("error", "Missing key or value", Thread.currentThread().getName()));
            }

            cache.addItem(item.key, item.value);

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("status", "success");
            responseMap.put("key", item.key);
            responseMap.put("value", item.value);
            responseMap.put("thread", Thread.currentThread().getName());
            return gson.toJson(responseMap);
        });

        // Endpoint to get an item from the cache
        get("/cache/:key", (request, response) -> {
            response.type("application/json");
            String key = request.params(":key");
            String value = cache.getItem(key);

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("key", key);
            responseMap.put("thread", Thread.currentThread().getName());

            if (value != null) {
                responseMap.put("status", "found");
                responseMap.put("value", value);
            } else {
                responseMap.put("status", "not_found");
                response.status(404); // Not Found
            }
            return gson.toJson(responseMap);
        });

        // Simple DTO for request body
    }

    static class CacheItem {
        String key;
        String value;
    }

    // Simple DTO for response body
    @SuppressWarnings("unused") // Fields are used by Gson
    static class StatusResponse {
        String status;
        String message;
        String key;
        String value;
        String thread;

        public StatusResponse(String status, String message, String thread) {
            this.status = status;
            this.message = message;
            this.thread = thread;
        }
         public StatusResponse(String status, String key, String value, String thread) {
            this.status = status;
            this.key = key;
            this.value = value;
            this.thread = thread;
        }
    }
}
