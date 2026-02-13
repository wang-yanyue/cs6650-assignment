package org.example.chatflow;

public enum MessageType {
    TEXT,
    JOIN,
    LEAVE;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            MessageType.valueOf(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}

