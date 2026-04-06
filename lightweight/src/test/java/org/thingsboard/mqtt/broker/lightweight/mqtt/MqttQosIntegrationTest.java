package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT QoS 0, 1, and 2 publish/deliver semantics — PROTO-02.
 */
class MqttQosIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testPublishQos0_deliveredToSubscriber() throws Exception {
        MqttClient subscriber = createClient("sub-qos0-1");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("qos/test0", 0, (t, m) -> received.set(m.getPayload()));

        MqttClient publisher = createClient("pub-qos0-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("qos/test0", "qos0-msg".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("qos0-msg");
    }

    @Test
    void testPublishQos1_pubackReceived_deliveredToSubscriber() throws Exception {
        MqttClient subscriber = createClient("sub-qos1-1");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("qos/test1", 1, (t, m) -> received.set(m.getPayload()));

        MqttClient publisher = createClient("pub-qos1-1");
        publisher.connect(defaultConnectOptions());
        // Paho blocks until PUBACK is received — if we get here, PUBACK was processed
        publisher.publish("qos/test1", "qos1-msg".getBytes(), 1, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("qos1-msg");
    }

    @Test
    void testPublishQos2_fullHandshake_deliveredOnceToSubscriber() throws Exception {
        MqttClient subscriber = createClient("sub-qos2-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("qos/test2", 2, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-qos2-1");
        publisher.connect(defaultConnectOptions());
        // Paho handles full PUBREC/PUBREL/PUBCOMP handshake
        publisher.publish("qos/test2", "qos2-msg".getBytes(), 2, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);
        assertThat(count.get()).isEqualTo(1); // delivered exactly once
    }

    @Test
    @Disabled("Requires raw socket test; Paho does not expose DUP flag control.")
    void testPublishQos2_dupRetransmit_notDeliveredTwice() {
        // Publisher retransmits PUBLISH QoS 2 with DUP=true before handshake completes.
        // Expected: subscriber still receives message only once (idempotent delivery).
        // Requires raw socket manipulation to send DUP flag — not possible with Paho.
    }

    @Test
    void testPublishQos_downgradeToSubscriberMaxQos() throws Exception {
        MqttClient subscriber = createClient("sub-downgrade-1");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<Integer> receivedQos = new AtomicReference<>();
        subscriber.subscribe("qos/downgrade", 0, (t, m) -> receivedQos.set(m.getQos())); // subscribe QoS 0

        MqttClient publisher = createClient("pub-downgrade-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("qos/downgrade", "downgrade".getBytes(), 2, false); // publish QoS 2

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedQos.get() != null);
        assertThat(receivedQos.get()).isEqualTo(0); // downgraded to subscriber's QoS 0
    }

}
