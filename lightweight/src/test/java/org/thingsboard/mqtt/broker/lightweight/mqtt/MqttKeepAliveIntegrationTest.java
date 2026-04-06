package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT keep-alive / PINGREQ / PINGRESP — PROTO-04.
 *
 * <p>All tests are disabled until Plan 03 implements the keep-alive idle state handler.
 */
class MqttKeepAliveIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 03")
    void testPingReqPingResp_keepAliveRenewed() {
        // Client connects with keepAlive=2 seconds, sends PINGREQ before timeout.
        // Expected: broker replies with PINGRESP; connection remains open.
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testKeepAliveExpiry_clientDisconnected() {
        // Client connects with keepAlive=1 second, then goes silent (no PINGREQ).
        // Expected per MQTT 3.1.1 spec section 3.1.2.10: broker disconnects client after
        // 1.5 × keepAlive seconds (1.5 s). Channel should be closed.
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testKeepAliveZero_noTimeout() {
        // Client connects with keepAlive=0 (no keep-alive mechanism).
        // Expected: broker does not apply a timeout; connection stays open indefinitely.
    }

}
