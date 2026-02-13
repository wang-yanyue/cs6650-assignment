package org.example.chatclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Assignment 1 Part 3: client with detailed per-message metrics and statistics.
 *
 * Differences from LoadTestClient (Part 2 basic):
 * - Tracks per-message latency based on server echo
 * - Writes CSV: {timestamp, messageType, latencyMs, statusCode, roomId}
 * - Computes mean/median/percentiles/min/max response times
 * - Computes throughput per room and message type distribution
 * - Writes a CSV for throughput over time (10-second buckets) for external charting
 *
 * NOTE: This is implemented as a separate main class so Part 2 remains unchanged.
 */
public class LoadTestClientPart3 {

    // --- Assignment configuration (same as Part 2) ---
    private static final long TOTAL_MESSAGES = 500_000L;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    private static final int MAIN_THREADS = 64;

    private static final int MAX_USER_ID = 100_000;
    private static final int MIN_USERNAME_LEN = 3;
    private static final int MAX_USERNAME_LEN = 20;
    private static final int ROOM_COUNT = 20;

    // messageType distribution: 90% TEXT, 5% JOIN, 5% LEAVE
    private static final double TEXT_PROB = 0.90;
    private static final double JOIN_PROB = 0.05; // cumulative 0.95 (JOIN)

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BlockingQueue<GeneratedMessage> MESSAGE_QUEUE = new LinkedBlockingQueue<>();

    // Global counters
    private static final AtomicLong SUCCESS_COUNT = new AtomicLong();
    private static final AtomicLong FAILURE_COUNT = new AtomicLong();
    private static final AtomicLong TOTAL_SENT = new AtomicLong();
    private static final AtomicInteger TOTAL_CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger TOTAL_RECONNECTS = new AtomicInteger();

    // Metrics collection
    private static final List<MessageMetric> METRICS =
            Collections.synchronizedList(new ArrayList<>());
    private static final Map<Integer, AtomicLong> ROOM_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> TYPE_COUNTS = new ConcurrentHashMap<>();

    // Throughput over time (configurable buckets, based on receive time)
    private static final Map<Long, AtomicLong> BUCKET_COUNTS = new ConcurrentHashMap<>();
    private static final int BUCKET_SECONDS = 1; // 1-second buckets give more chart points for short tests
    private static final long BUCKET_SIZE_NANOS = BUCKET_SECONDS * 1_000_000_000L;

    // Simple pool of 50 predefined messages
    private static final String[] MESSAGE_POOL = new String[50];

    static {
        for (int i = 0; i < MESSAGE_POOL.length; i++) {
            MESSAGE_POOL[i] = "Sample message " + (i + 1) + " - " + UUID.randomUUID();
        }
    }

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -cp client-part1.jar org.example.chatclient.LoadTestClientPart3 <wsBaseUrl>");
            System.err.println("Example: java -cp client-part1.jar org.example.chatclient.LoadTestClientPart3 ws://54.184.79.62:8081/chat");
            System.exit(1);
        }

        final String wsBaseUrl = args[0];
        if (!wsBaseUrl.startsWith("ws://") && !wsBaseUrl.startsWith("wss://")) {
            System.err.println("WebSocket base URL must start with ws:// or wss://");
            System.exit(1);
        }

        System.out.println("Starting Part 3 metrics client against: " + wsBaseUrl);
        System.out.println("Total messages: " + TOTAL_MESSAGES);

        // Prepare results directory
        Path resultsDir = Path.of("results");
        Files.createDirectories(resultsDir);

        long globalStartNanos = System.nanoTime();

        // Start single message generator thread
        Thread generatorThread = new Thread(new MessageGenerator(TOTAL_MESSAGES), "p3-message-generator");
        generatorThread.start();

        // --- Warmup phase (same pattern as Part 2, but metrics are also captured) ---
        long warmupStart = System.nanoTime();
        Thread[] warmupThreads = new Thread[WARMUP_THREADS];
        for (int i = 0; i < WARMUP_THREADS; i++) {
            SenderWorker worker = new SenderWorker(
                    wsBaseUrl,
                    WARMUP_MESSAGES_PER_THREAD,
                    true,
                    globalStartNanos
            );
            warmupThreads[i] = new Thread(worker, "p3-warmup-sender-" + i);
            warmupThreads[i].start();
        }
        for (Thread t : warmupThreads) {
            t.join();
        }
        long warmupEnd = System.nanoTime();
        long warmupSent = WARMUP_THREADS * (long) WARMUP_MESSAGES_PER_THREAD;

        System.out.println("Warmup phase (Part 3) complete.");
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
                    remainingMessages,
                    false,
                    globalStartNanos
            );
            mainThreads[i] = new Thread(worker, "p3-main-sender-" + i);
            mainThreads[i].start();
        }
        for (Thread t : mainThreads) {
            t.join();
        }
        long mainEnd = System.nanoTime();

        generatorThread.join();

        long totalSuccess = SUCCESS_COUNT.get();
        long totalFailure = FAILURE_COUNT.get();
        long runTimeNanos = mainEnd - warmupStart;
        double runTimeSeconds = runTimeNanos / 1_000_000_000.0;
        double throughput = totalSuccess / runTimeSeconds;

        System.out.println("==== Test Summary (Part 3 metrics) ====");
        System.out.println("Successful messages: " + totalSuccess);
        System.out.println("Failed messages: " + totalFailure);
        System.out.printf("Total runtime (s): %.3f%n", runTimeSeconds);
        System.out.printf("Overall throughput (msg/s): %.2f%n", throughput);
        System.out.println("Total connections opened: " + TOTAL_CONNECTIONS.get());
        System.out.println("Total reconnects: " + TOTAL_RECONNECTS.get());

        // --- Compute statistical metrics ---
        computeAndPrintStatistics(resultsDir, globalStartNanos, runTimeSeconds);
    }

    // ----------------------- Message generation -----------------------

    private static class MessageGenerator implements Runnable {
        private final long totalToGenerate;
        private final Random random = new Random();

        MessageGenerator(long totalToGenerate) {
            this.totalToGenerate = totalToGenerate;
        }

        @Override
        public void run() {
            for (long i = 0; i < totalToGenerate; i++) {
                try {
                    GeneratedMessage msg = generateMessage();
                    MESSAGE_QUEUE.put(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private GeneratedMessage generateMessage() {
            int userId = 1 + random.nextInt(MAX_USER_ID);
            String username = "user" + userId;
            if (username.length() < MIN_USERNAME_LEN) {
                username = String.format("user%02d", userId);
            }
            if (username.length() > MAX_USERNAME_LEN) {
                username = username.substring(0, MAX_USERNAME_LEN);
            }

            String message = MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)];
            int roomId = 1 + random.nextInt(ROOM_COUNT);
            String messageType = pickMessageType(random.nextDouble());
            String timestamp = ISO_FORMATTER.format(Instant.now());

            GeneratedMessage msg = new GeneratedMessage();
            msg.userId = String.valueOf(userId);
            msg.username = username;
            msg.message = message;
            msg.timestamp = timestamp;
            msg.messageType = messageType;
            msg.roomId = roomId;
            return msg;
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

    private static class GeneratedMessage {
        public String userId;
        public String username;
        public String message;
        public String timestamp;
        public String messageType;
        public int roomId;
    }

    // ----------------------- Sender worker with per-message metrics -----------------------

    private static class SenderWorker implements Runnable {
        private final String wsBaseUrl;
        private final long phaseMessagesTarget;
        private final boolean warmupPhase;
        private final long globalStartNanos;

        // Retry settings
        private static final int MAX_RETRIES = 5;
        private static final long INITIAL_BACKOFF_MILLIS = 50;

        SenderWorker(String wsBaseUrl, long phaseMessagesTarget, boolean warmupPhase, long globalStartNanos) {
            this.wsBaseUrl = wsBaseUrl;
            this.phaseMessagesTarget = phaseMessagesTarget;
            this.warmupPhase = warmupPhase;
            this.globalStartNanos = globalStartNanos;
        }

        @Override
        public void run() {
            long localSent = 0;
            WebSocketClient client = null;
            java.util.Random random = new java.util.Random();

            // Per-connection pending queue: messages sent but not yet acknowledged
            ConcurrentLinkedQueue<PendingMessage> pendingQueue = new ConcurrentLinkedQueue<>();

            try {
                String roomUrl = wsBaseUrl.endsWith("/")
                        ? wsBaseUrl + "1"
                        : wsBaseUrl + "/1";

                client = new WebSocketClient(new java.net.URI(roomUrl)) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        // no-op
                    }

                    @Override
                    public void onMessage(String message) {
                        long receiveNs = System.nanoTime();
                        PendingMessage pending = pendingQueue.poll();
                        if (pending == null) {
                            return;
                        }

                        String statusCode = "UNKNOWN";
                        try {
                            JsonNode node = OBJECT_MAPPER.readTree(message);
                            if (node.has("status")) {
                                statusCode = node.get("status").asText();
                            }
                            if (node.has("errorCode")) {
                                statusCode = node.get("errorCode").asText();
                            }
                        } catch (Exception ignored) {
                        }

                        long latencyNs = receiveNs - pending.sendTimeNs;
                        double latencyMs = latencyNs / 1_000_000.0;

                        // Per-message metrics record
                        MessageMetric metric = new MessageMetric();
                        metric.sendTimestampIso = ISO_FORMATTER.format(Instant.ofEpochMilli(pending.sendTimeNs / 1_000_000));
                        metric.messageType = pending.messageType;
                        metric.latencyMs = latencyMs;
                        metric.statusCode = statusCode;
                        metric.roomId = pending.roomId;
                        metric.receiveTimeNs = receiveNs;

                        METRICS.add(metric);
                        SUCCESS_COUNT.incrementAndGet();

                        // Room counts and type counts
                        ROOM_COUNTS.computeIfAbsent(pending.roomId, k -> new AtomicLong()).incrementAndGet();
                        TYPE_COUNTS.computeIfAbsent(pending.messageType, k -> new AtomicLong()).incrementAndGet();

                        // Throughput bucket
                        long bucketIndex = (receiveNs - globalStartNanos) / BUCKET_SIZE_NANOS;
                        BUCKET_COUNTS.computeIfAbsent(bucketIndex, k -> new AtomicLong()).incrementAndGet();
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

                connectBlocking(client);

                // Send a simple JOIN for this connection (not tracked in metrics)
                client.send(buildControlMessageJson("JOIN", random));

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

                    GeneratedMessage msg = MESSAGE_QUEUE.poll(500, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        continue;
                    }

                    String json = OBJECT_MAPPER.writeValueAsString(msg);
                    long sendNs = System.nanoTime();
                    PendingMessage pending = new PendingMessage();
                    pending.sendTimeNs = sendNs;
                    pending.messageType = msg.messageType;
                    pending.roomId = msg.roomId;
                    pendingQueue.add(pending);

                    boolean sent = sendWithRetry(client, json);
                    if (sent) {
                        TOTAL_SENT.incrementAndGet();
                        localSent++;
                    } else {
                        FAILURE_COUNT.incrementAndGet();
                    }
                }

                // LEAVE at end (not tracked)
                client.send(buildControlMessageJson("LEAVE", random));

            } catch (Exception e) {
                System.err.println("SenderWorker error: " + e.getMessage());
            } finally {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                    } catch (Exception ignore) {
                    }
                }
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
                    backoff *= 2;
                }
            }
            return false;
        }

        private String buildControlMessageJson(String type, java.util.Random random) {
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
                msg.message = type;
                msg.timestamp = ISO_FORMATTER.format(Instant.now());
                msg.messageType = type;
                msg.roomId = 1 + random.nextInt(ROOM_COUNT);

                return OBJECT_MAPPER.writeValueAsString(msg);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build control message JSON for type " + type, e);
            }
        }
    }

    private static class PendingMessage {
        long sendTimeNs;
        String messageType;
        int roomId;
    }

    private static class MessageMetric {
        String sendTimestampIso;
        String messageType;
        double latencyMs;
        String statusCode;
        int roomId;
        long receiveTimeNs;
    }

    // ----------------------- Statistics and CSV output -----------------------

    private static void computeAndPrintStatistics(Path resultsDir, long globalStartNanos, double totalRunSeconds) throws IOException {
        if (METRICS.isEmpty()) {
            System.err.println("No metrics collected; cannot compute statistics.");
            return;
        }

        // Sort latencies for percentiles
        List<Double> latenciesMs = new ArrayList<>(METRICS.size());
        for (MessageMetric m : METRICS) {
            latenciesMs.add(m.latencyMs);
        }
        Collections.sort(latenciesMs);

        double mean = latenciesMs.stream().mapToDouble(d -> d).average().orElse(0.0);
        double min = latenciesMs.get(0);
        double max = latenciesMs.get(latenciesMs.size() - 1);
        double median = percentile(latenciesMs, 50.0);
        double p95 = percentile(latenciesMs, 95.0);
        double p99 = percentile(latenciesMs, 99.0);

        System.out.println("---- Response Time Statistics (ms) ----");
        System.out.printf("Mean:   %.3f%n", mean);
        System.out.printf("Median: %.3f%n", median);
        System.out.printf("p95:    %.3f%n", p95);
        System.out.printf("p99:    %.3f%n", p99);
        System.out.printf("Min:    %.3f%n", min);
        System.out.printf("Max:    %.3f%n", max);

        // Throughput per room
        System.out.println("---- Throughput per Room ----");
        for (Map.Entry<Integer, AtomicLong> e : new TreeMap<>(ROOM_COUNTS).entrySet()) {
            double roomThroughput = e.getValue().get() / totalRunSeconds;
            System.out.printf("Room %d: %.2f msg/s (%d messages)%n",
                    e.getKey(), roomThroughput, e.getValue().get());
        }

        // Message type distribution
        System.out.println("---- Message Type Distribution ----");
        long totalMetrics = METRICS.size();
        for (Map.Entry<String, AtomicLong> e : TYPE_COUNTS.entrySet()) {
            double pct = (100.0 * e.getValue().get()) / totalMetrics;
            System.out.printf("%s: %d (%.2f%%)%n", e.getKey(), e.getValue().get(), pct);
        }

        // Write per-message CSV
        Path csvPath = resultsDir.resolve("part3-metrics.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("timestamp,messageType,latencyMs,statusCode,roomId");
            writer.newLine();
            for (MessageMetric m : METRICS) {
                writer.write(String.format(
                        "%s,%s,%.3f,%s,%d",
                        m.sendTimestampIso,
                        m.messageType,
                        m.latencyMs,
                        m.statusCode,
                        m.roomId
                ));
                writer.newLine();
            }
        }
        System.out.println("Per-message metrics written to: " + csvPath.toAbsolutePath());

        // Write throughput over time (10-second buckets)
        Path bucketsPath = resultsDir.resolve("part3-throughput-buckets.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(bucketsPath)) {
            writer.write("bucketStartElapsedSeconds,messages,throughputMsgPerSec");
            writer.newLine();

            long maxBucket = BUCKET_COUNTS.keySet().stream().mapToLong(v -> v).max().orElse(0L);
            for (long i = 0; i <= maxBucket; i++) {
                long count = BUCKET_COUNTS.getOrDefault(i, new AtomicLong(0)).get();
                double bucketThroughput = count / (double) BUCKET_SECONDS;
                long bucketStartElapsedSeconds = i * BUCKET_SECONDS;
                writer.write(String.format("%d,%d,%.3f", bucketStartElapsedSeconds, count, bucketThroughput));
                writer.newLine();
            }
        }
        System.out.println("Throughput buckets written to: " + bucketsPath.toAbsolutePath());
        if (BUCKET_COUNTS.size() <= 1) {
            System.out.println("Only one throughput bucket was generated; increase run time/messages for a richer chart.");
        }
        System.out.println("You can plot this CSV as a line chart (elapsed time vs throughput) in Excel/Sheets.");
    }

    private static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return 0.0;
        if (pct <= 0) return sorted.get(0);
        if (pct >= 100) return sorted.get(sorted.size() - 1);
        double index = (pct / 100.0) * (sorted.size() - 1);
        int i = (int) Math.floor(index);
        int j = (int) Math.ceil(index);
        if (i == j) return sorted.get(i);
        double w = index - i;
        return sorted.get(i) * (1.0 - w) + sorted.get(j) * w;
    }
}

