package org.example.chatflow;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public class ChatMessage {

    // Fields must match JSON keys for Jackson
    private String userId;      // 1-100000
    private String username;    // 3-20 chars, alphanumeric
    private String message;     // 1-500 chars
    private String timestamp;   // ISO-8601
    private String messageType; // TEXT|JOIN|LEAVE

    public ChatMessage() {
    }

    // Getters and setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    /**
     * Basic validation according to assignment rules.
     * Returns true if all fields are valid, false otherwise.
     */
    public boolean isValid() {
        try {
            // userId: 1-100000
            int id = Integer.parseInt(userId);
            if (id < 1 || id > 100000) {
                return false;
            }

            // username: 3-20 alphanumeric chars
            if (username == null || !username.matches("^[a-zA-Z0-9]{3,20}$")) {
                return false;
            }

            // message: 1-500 chars
            if (message == null || message.length() < 1 || message.length() > 500) {
                return false;
            }

            // timestamp: valid ISO-8601
            if (timestamp == null) {
                return false;
            }
            try {
                OffsetDateTime.parse(timestamp);
            } catch (DateTimeParseException ex) {
                return false;
            }

            // messageType: one of TEXT|JOIN|LEAVE
            if (!MessageType.isValid(messageType)) {
                return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

