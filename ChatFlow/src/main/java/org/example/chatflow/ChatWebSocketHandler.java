package org.example.chatflow;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Handles WebSocket connections for /chat/{roomId}.
 * Validates incoming ChatMessage payloads and echoes responses back to sender.
 * Also enforces a simple per-connection state machine for messageType:
 *   NOT_JOINED -> JOINED -> LEFT
 * Clients must send JOIN before TEXT/LEAVE, and LEAVE only after JOIN.
 */
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private enum SessionState {
        NOT_JOINED,
        JOINED,
        LEFT
    }

    private static final String SESSION_STATE_KEY = "chatState";

    private final ObjectMapper objectMapper;

    // roomId -> sessions in that room (for future extensions / broadcasts)
    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = (String) session.getAttributes().get("roomId");
        if (roomId == null) {
            // If for some reason roomId is missing, close the connection.
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException ignored) {
            }
            return;
        }

        // Initialize per-connection state
        session.getAttributes().put(SESSION_STATE_KEY, SessionState.NOT_JOINED);

        roomSessions
                .computeIfAbsent(roomId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(payload, ChatMessage.class);
        } catch (JsonProcessingException e) {
            sendError(session, "INVALID_JSON", "Malformed JSON payload");
            return;
        }

        if (!chatMessage.isValid()) {
            sendError(session, "INVALID_MESSAGE", "Validation failed for message fields");
            return;
        }

        // Enforce JOIN -> TEXT -> LEAVE order per connection
        SessionState state = (SessionState) session.getAttributes()
                .getOrDefault(SESSION_STATE_KEY, SessionState.NOT_JOINED);
        String type = chatMessage.getMessageType();

        switch (type) {
            case "JOIN" -> {
                if (state != SessionState.NOT_JOINED) {
                    sendError(session, "INVALID_STATE", "JOIN is only allowed once at the beginning of a session");
                    return;
                }
                state = SessionState.JOINED;
            }
            case "TEXT" -> {
                if (state != SessionState.JOINED) {
                    sendError(session, "INVALID_STATE", "TEXT is only allowed after JOIN and before LEAVE");
                    return;
                }
            }
            case "LEAVE" -> {
                if (state != SessionState.JOINED) {
                    sendError(session, "INVALID_STATE", "LEAVE is only allowed after JOIN");
                    return;
                }
                state = SessionState.LEFT;
            }
            default -> {
                // Should not happen due to isValid() + MessageType enum
            }
        }

        session.getAttributes().put(SESSION_STATE_KEY, state);

        // Build success response: echo back with server timestamp and status
        Map<String, Object> response = Map.of(
                "userId", chatMessage.getUserId(),
                "username", chatMessage.getUsername(),
                "message", chatMessage.getMessage(),
                "timestamp", chatMessage.getTimestamp(),
                "messageType", chatMessage.getMessageType(),
                "status", "OK",
                "serverTimestamp", OffsetDateTime.now().toString()
        );

        String json = objectMapper.writeValueAsString(response);
        sendSafely(session, json);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = (String) session.getAttributes().get("roomId");
        if (roomId != null) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    roomSessions.remove(roomId);
                }
            }
        }
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> error = Map.of(
                "status", "ERROR",
                "errorCode", code,
                "errorMessage", message,
                "serverTimestamp", OffsetDateTime.now().toString()
        );
        try {
            String json = objectMapper.writeValueAsString(error);
            sendSafely(session, json);
        } catch (JsonProcessingException e) {
            // Should not happen for our simple Map
        }
    }

    /**
     * Sends JSON to the session. Catches IOException (e.g. "Connection reset by peer")
     * when the client has already closed the connection, so we don't log huge stack traces.
     */
    private void sendSafely(WebSocketSession session, String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            // Client disconnected or connection reset; normal under load. Don't spam logs.
            if (session.isOpen()) {
                // Only log if we didn't expect this (session still open)
                // no-op or log at debug: "Failed to send: " + e.getMessage()
            }
        }
    }
}

