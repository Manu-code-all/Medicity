package com.medicity.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The video room's switchboard. Each visit has a room with at most two
 * places, the patient's and the doctor's; whatever one browser sends
 * (an offer, an answer, a network candidate, a hang-up) is passed to the
 * other unchanged. Audio and video never come here: once the browsers have
 * exchanged these few messages they talk to each other directly, encrypted.
 *
 * <p>The handshake needs a ticket from {@link VideoTickets}; nothing else
 * about the caller is trusted from the socket.
 */
@Component
@Slf4j
public class VideoSignalling extends TextWebSocketHandler implements HandshakeInterceptor {

    /** Only these are relayed; anything else is dropped, not echoed. */
    static final Set<String> RELAYED = Set.of("offer", "answer", "candidate", "hangup");
    static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private static final String VISIT = "visit";
    private static final String SIDE = "side";

    private final VideoTickets tickets;
    private final ObjectMapper json;
    private final Map<UUID, Map<VideoTickets.Side, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public VideoSignalling(VideoTickets tickets, ObjectMapper json) {
        this.tickets = tickets;
        this.json = json;
    }

    // --- handshake ------------------------------------------------------------

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        return tickets.redeem(ticket).map(p -> {
            attributes.put(VISIT, p.appointmentId());
            attributes.put(SIDE, p.side());
            return true;
        }).orElse(false);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }

    // --- the room -------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, MAX_MESSAGE_BYTES);
        UUID visit = visit(raw);
        VideoTickets.Side side = side(raw);
        Map<VideoTickets.Side, WebSocketSession> room = rooms.computeIfAbsent(visit, v -> new ConcurrentHashMap<>());
        // Rejoining (a reload, a dropped network) replaces the old connection.
        WebSocketSession previous = room.put(side, session);
        if (previous != null && previous.isOpen()) {
            previous.close(CloseStatus.POLICY_VIOLATION.withReason("Joined from another window"));
        }
        WebSocketSession other = room.get(opposite(side));
        send(session, json.createObjectNode().put("type", "joined").put("peerPresent", other != null));
        if (other != null) {
            send(other, json.createObjectNode().put("type", "peer-joined"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            raw.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        JsonNode body = json.readTree(message.getPayload());
        if (!RELAYED.contains(body.path("type").asText())) {
            return;
        }
        Map<VideoTickets.Side, WebSocketSession> room = rooms.get(visit(raw));
        WebSocketSession other = room == null ? null : room.get(opposite(side(raw)));
        if (other != null && other.isOpen()) {
            other.sendMessage(new TextMessage(message.getPayload()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) throws IOException {
        UUID visit = visit(raw);
        VideoTickets.Side side = side(raw);
        Map<VideoTickets.Side, WebSocketSession> room = rooms.get(visit);
        if (room == null) {
            return;
        }
        // Only if this connection still holds the place (not one replaced by a rejoin).
        WebSocketSession current = room.get(side);
        if (current != null && current.getId().equals(raw.getId())) {
            room.remove(side);
            WebSocketSession other = room.get(opposite(side));
            if (other != null && other.isOpen()) {
                send(other, json.createObjectNode().put("type", "peer-left"));
            }
        }
        rooms.computeIfPresent(visit, (v, r) -> r.isEmpty() ? null : r);
    }

    /** How many rooms have someone in them (for tests and a future metric). */
    int openRooms() {
        return rooms.size();
    }

    private void send(WebSocketSession session, ObjectNode body) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(body)));
    }

    private static UUID visit(WebSocketSession s) {
        return (UUID) s.getAttributes().get(VISIT);
    }

    private static VideoTickets.Side side(WebSocketSession s) {
        return (VideoTickets.Side) s.getAttributes().get(SIDE);
    }

    private static VideoTickets.Side opposite(VideoTickets.Side side) {
        return side == VideoTickets.Side.PATIENT ? VideoTickets.Side.DOCTOR : VideoTickets.Side.PATIENT;
    }
}
