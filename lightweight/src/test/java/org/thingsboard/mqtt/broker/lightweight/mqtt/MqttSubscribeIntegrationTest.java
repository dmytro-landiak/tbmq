package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT SUBSCRIBE / UNSUBSCRIBE / SUBACK — PROTO-03.
 *
 * <p>All tests are disabled until Plan 04 implements the subscription handler.
 *
 * <p>D-07 design decision: wildcard subscriptions are accepted (valid SUBACK) but
 * delivery is deferred beyond Phase 2 scope — wildcard tests assert SUBACK only.
 */
class MqttSubscribeIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 04")
    void testSubscribe_exactTopic_receivesMessages() {
        // Client subscribes to "test/topic" at QoS 1.
        // Expected: SUBACK with returnCode=1 (QoS 1 granted).
        // Publisher sends to "test/topic" — subscriber receives message.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testSubscribe_multipleTopics_subackContainsAllQos() {
        // Client subscribes to ["a/b" QoS 0, "c/d" QoS 1] in a single SUBSCRIBE packet.
        // Expected: SUBACK with two return codes [0x00, 0x01].
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testUnsubscribe_stopsReceivingMessages() {
        // Client subscribes to "test/unsub", unsubscribes.
        // Expected: UNSUBACK received; subsequent publishes to that topic not delivered.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testSubscribe_wildcardPlus_storedButNoDelivery() {
        // Client subscribes to "sensor/+/temp".
        // Expected: SUBACK with granted QoS (subscription stored).
        // Per D-07: no message delivery for wildcard subscriptions in Phase 2.
    }

    @Test
    @Disabled("Enabled in Plan 04")
    void testSubscribe_wildcardHash_storedButNoDelivery() {
        // Client subscribes to "sensor/#".
        // Expected: SUBACK with granted QoS (subscription stored).
        // Per D-07: no message delivery for wildcard subscriptions in Phase 2.
    }

}
