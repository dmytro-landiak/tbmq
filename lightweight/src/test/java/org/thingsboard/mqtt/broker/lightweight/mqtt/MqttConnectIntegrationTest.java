package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT CONNECT / CONNACK / DISCONNECT — PROTO-01 and TRAN-01.
 *
 * <p>All tests are disabled until Plan 03 implements the MQTT CONNECT handler.
 * When enabled, each test validates the exact behavior described in the method name.
 */
class MqttConnectIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 03")
    void testConnect_withValidClientId_returnsConnAck() {
        // Client connects with a valid non-empty clientId and cleanSession=true.
        // Expected: CONNACK with returnCode=0 (CONNECTION_ACCEPTED).
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testConnect_withEmptyClientIdAndCleanSession_assignsClientId() {
        // Client connects with empty clientId and cleanSession=true.
        // Expected per MQTT 3.1.1 spec section 3.1.3.1: broker assigns a unique clientId,
        // CONNACK returnCode=0. Subsequent operations using the assigned ID work normally.
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testConnect_secondConnectOnSameChannel_disconnects() {
        // Client sends a second CONNECT on an already-connected channel.
        // Expected per MQTT 3.1.1 spec section 3.1: broker MUST disconnect.
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testConnect_protocolVersionNotSupported_returnsUnacceptable() {
        // Client connects with an unsupported protocol version (e.g., MQTT 3.1.0 magic bytes
        // with version byte set to an unknown value).
        // Expected: CONNACK with returnCode=1 (UNACCEPTABLE_PROTOCOL_VERSION), then disconnect.
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testDisconnect_cleanDisconnect_closesChannel() {
        // Connected client sends DISCONNECT packet.
        // Expected: channel is closed gracefully, no LWT published.
    }

}
