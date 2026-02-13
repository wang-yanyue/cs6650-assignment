package org.example.chatclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multithreaded WebSocket client for Assignment 1 Part 2 (basic throughput).
 *
 * Phases:
 *  - Warmup: 32 threads, each sends 1000 messages then terminates.
 *  - Main: remaining messages sent with a configurable number of threads.
 *
 * A single dedicated generator thread produces ALL messages into a BlockingQueue.
 */
public class LoadTestClient {

    // --- Assignment configuration ---
    private static final long TOTAL_MESSAGES = 500_000L;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    private static final int MAIN_THREADS = 64; // you can tune this

    // Message distribution
    private static final int MAX_USER_ID = 100_000;
    private static final int MIN_USERNAME_LEN = 3;
    private static final int MAX_USERNAME_LEN = 20;
    private static final int ROOM_COUNT = 20;

    // messageType distribution: 90% TEXT, 5% JOIN, 5% LEAVE
    private static final double TEXT_PROB = 0.90;
    private static final double JOIN_PROB = 0.05; // cumulative 0.95 (JOIN)
    // remaining up to 1.0 is LEAVE

    // Shared objects
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BlockingQueue<String> MESSAGE_QUEUE = new LinkedBlockingQueue<>();

    // Counters
    private static final AtomicLong SUCCESS_COUNT = new AtomicLong();
    private static final AtomicLong FAILURE_COUNT = new AtomicLong();
    private static final AtomicLong TOTAL_SENT = new AtomicLong();
    private static final AtomicInteger TOTAL_CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger TOTAL_RECONNECTS = new AtomicInteger();

    // Simple pool of 50 predefined messages
    private static final String[] MESSAGE_POOL = new String[50];

    static {
        for (int i = 0; i < MESSAGE_POOL.length; i++) {
            MESSAGE_POOL[i] = "Sample message " + (i + 1) + " - " + UUID.randomUUID();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar client-part1.jar <wsBaseUrl>");
            System.err.println("Example: java -jar client-part1.jar ws://54.203.96.199:8081/chat");
            System.exit(1);
        }

        final String wsBaseUrl = args[0]; // e.g. ws://host:port/chat
        if (!wsBaseUrl.startsWith("ws://") && !wsBaseUrl.startsWith("wss://")) {
            System.err.println("WebSocket base URL must start with ws:// or wss://");
            System.exit(1);
        }

        System.out.println("Starting load test against: " + wsBaseUrl);
        System.out.println("Total messages: " + TOTAL_MESSAGES);

        // Start single message generator thread
        Thread generatorThread = new Thread(new MessageGenerator(TOTAL_MESSAGES), "message-generator");
        generatorThread.start();

        // --- Warmup phase ---
        long warmupStart = System.nanoTime();
        Thread[] warmupThreads = new Thread[WARMUP_THREADS];
        for (int i = 0; i < WARMUP_THREADS; i++) {
            SenderWorker worker = new SenderWorker(
                    wsBaseUrl,
                    WARMUP_MESSAGES_PER_THREAD,
                    true
            );
            warmupThreads[i] = new Thread(worker, "warmup-sender-" + i);
            warmupThreads[i].start();
        }
        for (Thread t : warmupThreads) {
            t.join();
        }
        long warmupEnd = System.nanoTime();
        long warmupSent = WARMUP_THREADS * (long) WARMUP_MESSAGES_PER_THREAD;

        System.out.println("Warmup phase complete.");
        System.out.printf("Warmup messages sent: %d%n", warmupSent);
        System.out.printf("Warmup duration (s): %.3f%n", (warmupEnd - warmupStart) / 1_000_000_000.0);
        System.out.printf("Warmup throughput (msg/s): %.2f%n",
                warmupSent / ((warmupEnd - warmupStart) / 1_000_000_000.0));

        // --- Main phase ---
        long mainStart = System.nanoTime();
        long remainingMessages = TOTAL_MESSAGES - warmupSent;
        if (remainingMessages < 0) {
            remainingMessages = 0;
        }

        Thread[] mainThreads = new Thread[MAIN_THREADS];
        for (int i = 0; i < MAIN_THREADS; i++) {
            SenderWorker worker = new SenderWorker(
                    wsBaseUrl,
                    remainingMessages, // each thread will stop when global TOTAL_SENT reaches TOTAL_MESSAGES
                    false
            );
            mainThreads[i] = new Thread(worker, "main-sender-" + i);
            mainThreads[i].start();
        }
        for (Thread t : mainThreads) {
            t.join();
        }
        long mainEnd = System.nanoTime();

        // Wait for generator to finish (it should have already)
        generatorThread.join();

        long totalSuccess = SUCCESS_COUNT.get();
        long totalFailure = FAILURE_COUNT.get();
        long runTimeNanos = mainEnd - warmupStart;
        double runTimeSeconds = runTimeNanos / 1_000_000_000.0;
        double throughput = totalSuccess / runTimeSeconds;

        System.out.println("==== Test Summary (Part 2 basic) ====");
        System.out.println("Successful messages: " + totalSuccess);
        System.out.println("Failed messages: " + totalFailure);
        System.out.printf("Total runtime (s): %.3f%n", runTimeSeconds);
        System.out.printf("Overall throughput (msg/s): %.2f%n", throughput);
        System.out.println("Total connections opened: " + TOTAL_CONNECTIONS.get());
        System.out.println("Total reconnects: " + TOTAL_RECONNECTS.get());
    }

    /**
     * Generates JSON messages and pushes them into a BlockingQueue.
     * Single dedicated thread per assignment spec.
     */
    private static class MessageGenerator implements Runnable {
        private final long totalToGenerate;
        private final Random random = new Random();
        private final DateTimeFormatter formatter =
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

        MessageGenerator(long totalToGenerate) {
            this.totalToGenerate = totalToGenerate;
        }

        @Override
        public void run() {
            for (long i = 0; i < totalToGenerate; i++) {
                try {
                    String json = generateMessageJson();
                    MESSAGE_QUEUE.put(json);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (JsonProcessingException e) {
                    // Should not happen often; skip this message
                    System.err.println("Failed to generate JSON: " + e.getMessage());
                }
            }
        }

        private String generateMessageJson() throws JsonProcessingException {
            int userId = 1 + random.nextInt(MAX_USER_ID);
            String username = "user" + userId;
            // Enforce username length bounds (3-20) just in case
            if (username.length() < MIN_USERNAME_LEN) {
                username = String.format("user%02d", userId);
            }
            if (username.length() > MAX_USERNAME_LEN) {
                username = username.substring(0, MAX_USERNAME_LEN);
            }

            String message = MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)];
            int roomId = 1 + random.nextInt(ROOM_COUNT);
            String messageType = pickMessageType(random.nextDouble());
            String timestamp = formatter.format(Instant.now());

            // Note: roomId is used for client-side distribution only; server room is chosen by URL.
            GeneratedMessage msg = new GeneratedMessage();
            msg.userId = String.valueOf(userId);
            msg.username = username;
            msg.message = message;
            msg.timestamp = timestamp;
            msg.messageType = messageType;
            msg.roomId = roomId;

            return OBJECT_MAPPER.writeValueAsString(msg);
        }

        private String pickMessageType(double p) {
            if (p < TEXT_PROB) {
                return "TEXT";
            } else if (p < TEXT_PROB + JOIN_PROB) {
                return "JOIN";
            } else {
                return "LEAVE";
            }
        }
    }

    /**
     * Single message record used for JSON serialization.
     */
    private static class GeneratedMessage {
        public String userId;
        public String username;
        public String message;
        public String timestamp;
        public String messageType;
        public int roomId;
    }

    /**
     * Worker that maintains one WebSocket connection and sends messages pulled from the shared queue.
     * Includes basic retry and reconnection logic.
     *
     * Per-connection message order (to match server state machine):
     *   1) JOIN
     *   2) many TEXT messages
     *   3) LEAVE
     */
    private static class SenderWorker implements Runnable {
        private final String wsBaseUrl;
        private final long phaseMessagesTarget;
        private final boolean warmupPhase;

        // Retry settings
        private static final int MAX_RETRIES = 5;
        private static final long INITIAL_BACKOFF_MILLIS = 50;

        SenderWorker(String wsBaseUrl, long phaseMessagesTarget, boolean warmupPhase) {
            this.wsBaseUrl = wsBaseUrl;
            this.phaseMessagesTarget = phaseMessagesTarget;
            this.warmupPhase = warmupPhase;
        }

        @Override
        public void run() {
            long localSent = 0;
            WebSocketClient client = null;
            java.util.Random random = new java.util.Random();
            DateTimeFormatter formatter =
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

            try {
                // For simplicity, each worker uses room 1; room distribution is handled in JSON field.
                String roomUrl = wsBaseUrl.endsWith("/")
                        ? wsBaseUrl + "1"
                        : wsBaseUrl + "/1";
                client = createClient(roomUrl);
                connectBlocking(client);

                // 1) Send JOIN once at the beginning of the connection
                String joinJson = buildControlMessageJson("JOIN", random, formatter);
                boolean joinSent = sendWithRetry(client, joinJson);
                if (!joinSent) {
                    FAILURE_COUNT.incrementAndGet();
                    return; // cannot proceed without JOIN
                }

                // 2) Send TEXT messages from the shared queue
                while (true) {
                    if (warmupPhase) {
                        if (localSent >= phaseMessagesTarget) {
                            break;
                        }
                    } else {
                        long globalSent = TOTAL_SENT.get();
                        if (globalSent >= TOTAL_MESSAGES) {
                            break;
                        }
                    }

                    String json = MESSAGE_QUEUE.poll(500, TimeUnit.MILLISECONDS);
                    if (json == null) {
                        // No message ready yet, loop again
                        continue;
                    }

                    boolean sent = sendWithRetry(client, json);
                    if (sent) {
                        SUCCESS_COUNT.incrementAndGet();
                        TOTAL_SENT.incrementAndGet();
                        localSent++;
                    } else {
                        FAILURE_COUNT.incrementAndGet();
                    }
                }

                // 3) Send LEAVE once at the end of the connection
                String leaveJson = buildControlMessageJson("LEAVE", random, formatter);
                boolean leaveSent = sendWithRetry(client, leaveJson);
                if (!leaveSent) {
                    FAILURE_COUNT.incrementAndGet();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        private WebSocketClient createClient(String url) throws RuntimeException {
            try {
                WebSocketClient client = new WebSocketClient(new URI(url)) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        // no-op
                    }

                    @Override
                    public void onMessage(String message) {
                        // We don't need to process echo responses for Part 2 basic metrics.
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        // no-op
                    }

                    @Override
                    public void onError(Exception ex) {
                        System.err.println("WebSocket error: " + ex.getMessage());
                    }
                };
                return client;
            } catch (URISyntaxException e) {
                throw new RuntimeException("Invalid WebSocket URL: " + url, e);
            }
        }

        private void connectBlocking(WebSocketClient client) {
            try {
                TOTAL_CONNECTIONS.incrementAndGet();
                client.connectBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean sendWithRetry(WebSocketClient client, String json) {
            long backoff = INITIAL_BACKOFF_MILLIS;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (!client.isOpen()) {
                        TOTAL_RECONNECTS.incrementAndGet();
                        client.reconnectBlocking();
                    }
                    client.send(json);
                    return true;
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES) {
                        System.err.println("Failed to send message after retries: " + e.getMessage());
                        return false;
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    backoff *= 2; // exponential backoff
                }
            }
            return false;
        }

        /**
         * Builds a JOIN or LEAVE control message JSON that passes server validation.
         */
        private String buildControlMessageJson(String type,
                                              java.util.Random random,
                                              DateTimeFormatter formatter) {
            try {
                int userId = 1 + random.nextInt(MAX_USER_ID);
                String username = "user" + userId;
                if (username.length() < MIN_USERNAME_LEN) {
                    username = String.format("user%02d", userId);
                }
                if (username.length() > MAX_USERNAME_LEN) {
                    username = username.substring(0, MAX_USERNAME_LEN);
                }

                GeneratedMessage msg = new GeneratedMessage();
                msg.userId = String.valueOf(userId);
                msg.username = username;
                msg.message = type; // simple non-empty message
                msg.timestamp = formatter.format(Instant.now());
                msg.messageType = type;
                msg.roomId = 1 + random.nextInt(ROOM_COUNT);

                return OBJECT_MAPPER.writeValueAsString(msg);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build control message JSON for type " + type, e);
            }
        }
    }
}

