package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT retained messages — PROTO-05.
 *
 * <p>All tests are disabled until Plan 05 implements retained message storage and delivery.
 * Retained messages are stored in memory for the broker lifetime (no persistence across restarts
 * in R1 per project constraints).
 */
class MqttRetainedMsgIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 05")
    void testRetainedMessage_deliveredOnSubscribe() {
        // Publisher sends PUBLISH with RETAIN=true to "sensor/temp".
        // Subscriber (different client) subscribes to "sensor/temp" after the publish.
        // Expected: broker delivers the retained message immediately upon subscribe.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testRetainedMessage_emptyPayloadClearsRetained() {
        // Publisher sends PUBLISH with RETAIN=true and empty payload to "sensor/temp".
        // Expected per MQTT 3.1.1 spec section 3.3.1.3: retained message for that topic is deleted.
        // New subscriber to "sensor/temp" receives nothing.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testRetainedMessage_deliveredWithRetainFlag() {
        // Subscriber subscribes and receives a retained message.
        // Expected: the PUBLISH delivered to the subscriber has RETAIN=true.
    }

    @Test
    @Disabled("Enabled in Plan 05")
    void testRetainedMessage_qosDowngradeToSubscriptionQos() {
        // Publisher retains a message at QoS 2 on "sensor/temp".
        // Subscriber subscribes at QoS 1.
        // Expected: delivered at QoS 1 (downgraded to subscription QoS).
    }

}
