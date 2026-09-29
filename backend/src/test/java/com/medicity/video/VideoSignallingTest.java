package com.medicity.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The switchboard on its own: who hears what, with fake sockets. */
@DisplayName("Video visits: the signalling relay")
class VideoSignallingTest {

    private VideoSignalling relay;
    private final UUID visit = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        relay = new VideoSignalling(mock(VideoTickets.class), new ObjectMapper());
    }

    @Test
    @DisplayName("each side learns when the other arrives, and an offer reaches only the other side")
    void relaysBetweenTheTwo() throws Exception {
        WebSocketSession patient = socket("p1", VideoTickets.Side.PATIENT);
        WebSocketSession doctor = socket("d1", VideoTickets.Side.DOCTOR);

        relay.afterConnectionEstablished(patient);
        assertThat(sent(patient)).containsExactly("{\"type\":\"joined\",\"peerPresent\":false}");

        relay.afterConnectionEstablished(doctor);
        assertThat(sent(doctor)).contains("{\"type\":\"joined\",\"peerPresent\":true}");
        assertThat(sent(patient)).contains("{\"type\":\"peer-joined\"}");

        String offer = "{\"type\":\"offer\",\"sdp\":\"v=0...\"}";
        relay.handleTextMessage(patient, new TextMessage(offer));
        assertThat(sent(doctor)).contains(offer);
        assertThat(sent(patient)).doesNotContain(offer);
    }

    @Test
    @DisplayName("anything but offer, answer, candidate and hang-up is dropped")
    void dropsUnknownMessages() throws Exception {
        WebSocketSession patient = socket("p1", VideoTickets.Side.PATIENT);
        WebSocketSession doctor = socket("d1", VideoTickets.Side.DOCTOR);
        relay.afterConnectionEstablished(patient);
        relay.afterConnectionEstablished(doctor);

        relay.handleTextMessage(patient, new TextMessage("{\"type\":\"joined\",\"peerPresent\":false}"));
        relay.handleTextMessage(patient, new TextMessage("{\"type\":\"admin\",\"cmd\":\"x\"}"));

        assertThat(sent(doctor)).noneMatch(m -> m.contains("admin") || m.contains("\"peerPresent\":false"));
    }

    @Test
    @DisplayName("when one side leaves the other is told, and the room is gone once both have left")
    void leaving() throws Exception {
        WebSocketSession patient = socket("p1", VideoTickets.Side.PATIENT);
        WebSocketSession doctor = socket("d1", VideoTickets.Side.DOCTOR);
        relay.afterConnectionEstablished(patient);
        relay.afterConnectionEstablished(doctor);

        relay.afterConnectionClosed(doctor, CloseStatus.NORMAL);
        assertThat(sent(patient)).contains("{\"type\":\"peer-left\"}");
        relay.afterConnectionClosed(patient, CloseStatus.NORMAL);
        assertThat(relay.openRooms()).isZero();
    }

    @Test
    @DisplayName("rejoining from another window replaces the old connection instead of adding a third")
    void rejoinReplaces() throws Exception {
        WebSocketSession first = socket("p1", VideoTickets.Side.PATIENT);
        WebSocketSession second = socket("p2", VideoTickets.Side.PATIENT);
        WebSocketSession doctor = socket("d1", VideoTickets.Side.DOCTOR);
        relay.afterConnectionEstablished(first);
        relay.afterConnectionEstablished(second);
        relay.afterConnectionEstablished(doctor);

        verify(first).close(any(CloseStatus.class));
        // The old window closing later does not tell the doctor the patient left.
        relay.afterConnectionClosed(first, CloseStatus.NORMAL);
        assertThat(sent(doctor)).doesNotContain("{\"type\":\"peer-left\"}");

        relay.handleTextMessage(doctor, new TextMessage("{\"type\":\"answer\",\"sdp\":\"x\"}"));
        assertThat(sent(second)).contains("{\"type\":\"answer\",\"sdp\":\"x\"}");
        verify(first, never()).sendMessage(new TextMessage("{\"type\":\"answer\",\"sdp\":\"x\"}"));
    }

    private WebSocketSession socket(String id, VideoTickets.Side side) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>(Map.of("visit", visit, "side", side));
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(attributes);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> sent(WebSocketSession s) throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        try {
            verify(s, atLeastOnce()).sendMessage(captor.capture());
        } catch (AssertionError none) {
            return List.of();
        }
        return captor.getAllValues().stream().map(m -> (String) m.getPayload()).toList();
    }
}
