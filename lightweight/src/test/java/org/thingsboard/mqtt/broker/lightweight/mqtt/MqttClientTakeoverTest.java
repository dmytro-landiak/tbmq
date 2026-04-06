package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT client takeover (duplicate clientId) — PROTO-11.
 *
 * <p>All tests are disabled until Plan 05 implements client takeover logic.
 *
 * <p>Per MQTT 3.1.1 spec section 3.1.4: if a client connects using a clientId that is already
 * connected, the broker MUST disconnect the existing connection. The new connection takes over.
 */
class MqttClientTakeoverTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 05")
    void testClientTakeover_newConnectionReplacesOld() {
        // Two clients connect with the same clientId.
        // Expected: first connection is closed by the broker; second connection is active.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testClientTakeover_lwtNotDeliveredForDisplacedSession() {
        // First client connects with a will message and is displaced by a second client
        // with the same clientId.
        // Expected per MQTT spec: the LWT of the displaced session is NOT published
        // (takeover is not an ungraceful disconnect per broker discretion; clean takeover
        // suppresses the LWT to avoid spurious offline notifications).
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testClientTakeover_newSessionFunctional() {
        // After takeover, the new session can subscribe and receive messages normally.
        // Expected: publish to subscribed topic is delivered to the new session.
    }

}
