package org.example.chatflow;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriTemplate;

/**
 * Extracts {roomId} from the WebSocket handshake URL (/chat/{roomId})
 * and stores it as a session attribute so the handler can access it.
 */
public class RoomIdHandshakeInterceptor implements HandshakeInterceptor {

    private static final UriTemplate ROOM_TEMPLATE = new UriTemplate("/chat/{roomId}");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        Map<String, String> vars = ROOM_TEMPLATE.match(path);
        String roomId = vars.get("roomId");
        attributes.put("roomId", roomId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}

