package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT Last Will and Testament (LWT) — PROTO-06.
 *
 * <p>All tests are disabled until Plan 05 implements LWT publish-on-disconnect logic.
 */
class MqttLwtIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 05")
    void testLwt_ungracefulDisconnect_willMessageDelivered() {
        // Client connects with a will message configured on topic "lwt/status".
        // A second client subscribes to "lwt/status".
        // First client disconnects ungracefully (e.g., TCP close without DISCONNECT packet).
        // Expected: will message is published to "lwt/status" and delivered to subscriber.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testLwt_keepAliveExpiry_willMessageDelivered() {
        // Client connects with a will message and keepAlive=1 second, then goes silent.
        // A second client subscribes to the will topic.
        // Expected: after keep-alive expiry, broker publishes the will message.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testLwt_cleanDisconnect_willMessageNotDelivered() {
        // Client connects with a will message, then sends DISCONNECT packet gracefully.
        // Expected per MQTT 3.1.1 spec section 3.14: will message is NOT published
        // on clean disconnect.
    }

}
