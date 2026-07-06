package com.exam.backend.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * STOMP-over-WebSocket configuration for the Real-Time Live Proctoring feature.
 *
 * Endpoint (handshake):
 *   /ws            — SockJS-enabled STOMP endpoint. Because the app runs under the
 *                    "/QuickScreen" context-path, the browser connects to
 *                    {BACKEND_URL}/ws (same base the REST calls use).
 *
 * Destinations:
 *   Client → server (prefix "/app", handled by @MessageMapping):
 *     /app/presence/join     — candidate announces it is taking the exam
 *     /app/presence/leave    — candidate leaves (also handled on disconnect)
 *     /app/presence/sync     — admin asks for the current roster on connect
 *     /app/signal            — WebRTC offer / answer / ice-candidate relay
 *
 *   Server → client (simple broker, prefix "/topic"):
 *     /topic/presence            — broadcast roster of active candidates (admins subscribe)
 *     /topic/candidate/{key}     — messages routed TO a specific candidate (offer + admin ICE)
 *     /topic/admin/{key}         — messages routed TO the admin viewing {key} (answer + candidate ICE)
 *
 * We deliberately use plain topic destinations keyed by the candidate's sessionKey
 * rather than Spring "user" destinations, so no authenticated Principal is required
 * on the anonymous candidate WebSocket — the sessionKey itself is the routing id.
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        log.info("Registering STOMP endpoint /ws (SockJS enabled) for live proctoring");
        registry.addEndpoint("/ws")
                // Mirror CorsConfig — these are the only origins allowed to open a socket.
                .setAllowedOriginPatterns(
                        "https://hr-orbit.azalio.io",
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        // HTTPS dev origins — the Vite server now runs over HTTPS so the
                        // admin gets a secure context for camera/mic. The proxied handshake
                        // still carries the browser's https:// Origin, so allow it here too.
                        "https://localhost:*",
                        "https://127.0.0.1:*"
                        // LAN proctoring: candidates open the exam from the Vite host's own
                        // LAN IP (e.g. https://172.15.0.109:5173), so the WebSocket handshake
                        // Origin is that host — not localhost. Allow the local /16 subnets the
                        // dev server binds to. Add your subnet here if it differs.
//                        "http://172.15.*.*:*",
//                        "https://172.15.*.*:*",
//                        "http://172.23.*.*:*",
//                        "https://172.23.*.*:*"
                )
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory simple broker is enough for signaling + presence fan-out.
        //
        // HEARTBEATS (critical for live proctoring): once WebRTC media is flowing the
        // STOMP channel goes idle — no more offers/ICE to send. Without a heartbeat the
        // idle WebSocket is silently dropped by the proxy/OS after a while, the live
        // view stalls on "Waiting for candidate stream…", and stompjs thrashes on
        // reconnect. A 10s server heartbeat keeps the socket alive AND lets either side
        // detect a real drop within ~10s for a clean, fast reconnect.
        // NOTE: server-sent heartbeats REQUIRE a TaskScheduler — without it the broker
        // silently advertises heart-beat:0,0 (disabled), which is the bug we are fixing.
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] { 10000, 10000 })
                .setTaskScheduler(heartbeatScheduler());
        // Client sends to destinations prefixed with /app → @MessageMapping handlers.
        registry.setApplicationDestinationPrefixes("/app");
    }

    /** Dedicated scheduler that drives the simple broker's STOMP heartbeats. */
    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Raise the STOMP message/buffer size limits well above the WebRTC payload size.
     *
     * A WebRTC offer/answer SDP — especially with several pre-negotiated m-lines and
     * bundled codecs/candidates — easily exceeds Spring's defaults (message size 64KB,
     * send buffer 512KB). More importantly the underlying container text buffer (see
     * {@link #createWebSocketContainer()}) defaults to only 8KB, which made the server
     * close the socket with code 1009 ("decoded text message was too big") the instant
     * the admin published its offer — killing live proctoring. These limits give ample
     * headroom for the largest signaling messages.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(512 * 1024);       // max inbound STOMP message (was 64KB)
        registry.setSendBufferSizeLimit(2 * 1024 * 1024);
        registry.setSendTimeLimit(20 * 1000);
    }

    /**
     * The raw container-level WebSocket text/binary buffer. Tomcat defaults to 8192
     * bytes, which is smaller than a typical WebRTC offer — the cause of the 1009
     * "message too big" disconnects. Bump it so large SDP/answer frames fit.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }
}
