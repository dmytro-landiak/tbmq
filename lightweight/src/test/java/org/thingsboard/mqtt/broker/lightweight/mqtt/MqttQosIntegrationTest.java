package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT QoS 0, 1, and 2 publish/deliver semantics — PROTO-02.
 *
 * <p>All tests are disabled until Plan 04 implements the pub/sub message dispatch pipeline.
 */
class MqttQosIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 04")
    void testPublishQos0_deliveredToSubscriber() {
        // Publisher connects, subscriber connects and subscribes to topic.
        // Publisher sends PUBLISH QoS 0 message.
        // Expected: subscriber receives message exactly once, no PUBACK.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testPublishQos1_pubackReceived_deliveredToSubscriber() {
        // Publisher sends PUBLISH QoS 1 to subscribed topic.
        // Expected: broker sends PUBACK to publisher; subscriber receives message.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testPublishQos2_fullHandshake_deliveredOnceToSubscriber() {
        // Publisher sends PUBLISH QoS 2; full PUBREC/PUBREL/PUBCOMP handshake completes.
        // Expected: subscriber receives message exactly once.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testPublishQos2_dupRetransmit_notDeliveredTwice() {
        // Publisher retransmits PUBLISH QoS 2 with DUP=true before handshake completes.
        // Expected: subscriber still receives message only once (idempotent delivery).
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testPublishQos_downgradeToSubscriberMaxQos() {
        // Publisher sends PUBLISH QoS 2 to a subscriber that subscribed with QoS 1.
        // Expected per MQTT spec: delivered to subscriber at QoS 1 (downgraded).
    }

}
