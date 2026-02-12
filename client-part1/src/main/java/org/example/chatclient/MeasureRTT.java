package org.example.chatclient;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Measures connection time and single-message round-trip time (RTT) for Little's Law analysis.
 * Uses two WebSocket clients:
 * - Client A sends a TEXT message
 * - Client B receives it and immediately sends an ACK TEXT message back
 * - Client A measures RTT as send(A) -> receive ACK(A)
 *
 * This works even if the server does NOT echo messages back to the sender.
 *
 * Usage: run with WebSocket base URL as first argument.
 * Example: mvn exec:java -Dexec.mainClass="org.example.chatclient.MeasureRTT" -Dexec.args="ws://54.184.79.62:8081/chat"
 */
public class MeasureRTT {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final int RTT_SAMPLES = 20;
    private static final int ROOM_ID = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MeasureRTT <wsBaseUrl>");
            System.err.println("Example: MeasureRTT ws://54.184.79.62:8081/chat");
            System.exit(1);
        }

        String base = args[0].trim();
        if (!base.endsWith("/")) base = base + "/";
        String url = base + ROOM_ID;

        System.out.println("Measuring against: " + url);
        System.out.println("----------------------------------------");

        // Shared state for measuring RTT at client A
        long[] sendTimeNs = { 0 };
        String[] expectedAckToken = { null };
        CountDownLatch[] ackLatchHolder = { null };
        List<Long> rttList = new ArrayList<>();

        // Client A: measures RTT by waiting for "ACK:<token>"
        var clientA = new WebSocketClient(new URI(url)) {
            @Override
            public void onOpen(ServerHandshake h) {}

            @Override
            public void onMessage(String msg) {
                String token = expectedAckToken[0];
                CountDownLatch latch = ackLatchHolder[0];
                if (msg != null && token != null && latch != null && msg.contains("ACK:" + token) && sendTimeNs[0] > 0) {
                    long recv = System.nanoTime();
                    rttList.add(recv - sendTimeNs[0]);
                    latch.countDown();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {
                System.err.println("Error: " + ex.getMessage());
            }
        };

        // Client B: listens for "MEASURE:<token>" and replies with "ACK:<token>"
        var clientB = new WebSocketClient(new URI(url)) {
            @Override
            public void onOpen(ServerHandshake h) {}

            @Override
            public void onMessage(String msg) {
                if (msg == null) return;
                int idx = msg.indexOf("MEASURE:");
                if (idx < 0) return;

                // Try to extract token after "MEASURE:" up to the next quote (JSON string end)
                int start = idx + "MEASURE:".length();
                int end = msg.indexOf('"', start);
                if (end < 0) return;
                String token = msg.substring(start, end);

                try {
                    this.send(buildMessage("TEXT", "ACK:" + token, ROOM_ID, "2", "user2"));
                } catch (Exception ignored) {
                    // Best-effort ACK; if this fails we'll timeout on client A
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };

        // 1) Connection time (measure both, report A as primary)
        long aStart = System.nanoTime();
        clientA.connectBlocking(10, TimeUnit.SECONDS);
        double connAms = (System.nanoTime() - aStart) / 1_000_000.0;

        long bStart = System.nanoTime();
        clientB.connectBlocking(10, TimeUnit.SECONDS);
        double connBms = (System.nanoTime() - bStart) / 1_000_000.0;

        if (!clientA.isOpen() || !clientB.isOpen()) {
            System.err.println("Connection failed (clientA open=" + clientA.isOpen() + ", clientB open=" + clientB.isOpen() + ").");
            System.exit(1);
        }

        System.out.printf("Connection time A: %.2f ms%n", connAms);
        System.out.printf("Connection time B: %.2f ms%n", connBms);

        // 2) JOIN both clients, then measure RTT using A -> B ACK -> A
        clientA.send(buildMessage("JOIN", "JOIN", ROOM_ID, "1", "user1"));
        clientB.send(buildMessage("JOIN", "JOIN", ROOM_ID, "2", "user2"));
        Thread.sleep(200);

        for (int i = 0; i < RTT_SAMPLES; i++) {
            String token = i + "-" + System.nanoTime();
            expectedAckToken[0] = token;
            ackLatchHolder[0] = new CountDownLatch(1);
            sendTimeNs[0] = System.nanoTime();

            // A sends message with token; B will reply with ACK:token
            clientA.send(buildMessage("TEXT", "MEASURE:" + token, ROOM_ID, "1", "user1"));

            if (!ackLatchHolder[0].await(5, TimeUnit.SECONDS)) {
                System.err.println("Timeout waiting for ACK " + (i + 1));
            }
        }

        // Clean shutdown (best effort)
        try {
            clientA.send(buildMessage("LEAVE", "LEAVE", ROOM_ID, "1", "user1"));
            clientB.send(buildMessage("LEAVE", "LEAVE", ROOM_ID, "2", "user2"));
        } catch (Exception ignored) {}

        clientA.closeBlocking();
        clientB.closeBlocking();

        if (rttList.size() < RTT_SAMPLES) {
            System.err.println("Warning: only " + rttList.size() + " RTT samples (expected " + RTT_SAMPLES + ")");
        }
        if (rttList.isEmpty()) {
            System.err.println("No RTT samples collected.");
            System.exit(1);
        }

        double avgMs = rttList.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        long minNs = rttList.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxNs = rttList.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.println("----------------------------------------");
        System.out.printf("Connection time A (ms):   %.2f%n", connAms);
        System.out.printf("Samples (TEXT RTT):       %d%n", rttList.size());
        System.out.printf("Average RTT (ms):         %.2f%n", avgMs);
        System.out.printf("Min RTT (ms):             %.2f%n", minNs / 1_000_000.0);
        System.out.printf("Max RTT (ms):             %.2f%n", maxNs / 1_000_000.0);
        System.out.println("----------------------------------------");
        System.out.println("Copy the values above for your Little's Law writeup.");
    }

    private static String buildMessage(String messageType, String body, int roomId, String userId, String username) {
        String ts = ISO.format(Instant.now());
        String escaped = body.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format(
            "{\"userId\":\"%s\",\"username\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"messageType\":\"%s\",\"roomId\":%d}",
            userId, username, escaped, ts, messageType, roomId
        );
    }
}
