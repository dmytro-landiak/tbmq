package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for MQTT retained messages — PROTO-05.
 *
 * <p>Tests verify that retained messages are stored, delivered to new subscribers,
 * cleared on empty payload, and delivered with correct QoS downgrade and retain flag.
 */
class MqttRetainedMsgIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testRetainedMessage_deliveredOnSubscribe() throws Exception {
        // Publisher sends PUBLISH with RETAIN=true to "retain/topic".
        MqttClient publisher = createClient("pub-retain");
        publisher.connect(defaultConnectOptions());

        MqttMessage retainedMsg = new MqttMessage("retained-value".getBytes());
        retainedMsg.setQos(0);
        retainedMsg.setRetained(true);
        publisher.publish("retain/topic", retainedMsg);
        publisher.disconnect();

        // Subscriber subscribes AFTER the publisher disconnects.
        // Expected: broker delivers the retained message immediately upon subscribe.
        MqttClient subscriber = createClient("sub-retain");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("retain/topic", 0, (topic, msg) -> received.set(msg));

        await().atMost(5, SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get().getPayload())).isEqualTo("retained-value");
    }

    @Test
    void testRetainedMessage_emptyPayloadClearsRetained() throws Exception {
        // Publisher sends retain=true, then sends empty payload retain=true (clears it).
        MqttClient publisher = createClient("pub-retain-clear");
        publisher.connect(defaultConnectOptions());

        MqttMessage setMsg = new MqttMessage("value".getBytes());
        setMsg.setQos(0);
        setMsg.setRetained(true);
        publisher.publish("retain/clear", setMsg);

        MqttMessage clearMsg = new MqttMessage(new byte[0]);
        clearMsg.setQos(0);
        clearMsg.setRetained(true);
        publisher.publish("retain/clear", clearMsg);
        publisher.disconnect();

        // Subscriber subscribes — should receive nothing (retained message was cleared).
        MqttClient subscriber = createClient("sub-retain-clear");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("retain/clear", 0, (topic, msg) -> count.incrementAndGet());

        Thread.sleep(1000);
        assertThat(count.get()).isEqualTo(0); // no retained message delivered
    }

    @Test
    void testRetainedMessage_deliveredWithRetainFlag() throws Exception {
        // Publisher sends PUBLISH with RETAIN=true.
        MqttClient publisher = createClient("pub-retain-flag");
        publisher.connect(defaultConnectOptions());

        MqttMessage retainedMsg = new MqttMessage("flagged".getBytes());
        retainedMsg.setQos(0);
        retainedMsg.setRetained(true);
        publisher.publish("retain/flag", retainedMsg);
        publisher.disconnect();

        // Subscriber subscribes — retained message should have retain=true.
        MqttClient subscriber = createClient("sub-retain-flag");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("retain/flag", 0, (topic, msg) -> received.set(msg));

        await().atMost(5, SECONDS).until(() -> received.get() != null);
        assertThat(received.get().isRetained()).isTrue(); // retain flag set on delivery
    }

    @Test
    void testRetainedMessage_qosDowngradeToSubscriptionQos() throws Exception {
        // Publisher retains a message at QoS 1 on "retain/qos".
        // (QoS 2 retained with QoS 0 subscribe is complex due to QoS 2 handshake timing;
        //  use QoS 1 publish with QoS 0 subscribe to test downgrade)
        MqttClient publisher = createClient("pub-retain-qos");
        MqttConnectOptions opts = defaultConnectOptions();
        publisher.connect(opts);

        MqttMessage retainedMsg = new MqttMessage("qos-test".getBytes());
        retainedMsg.setQos(1);
        retainedMsg.setRetained(true);
        publisher.publish("retain/qos", retainedMsg);
        publisher.disconnect();

        // Subscriber subscribes at QoS 0 — delivery should be downgraded to QoS 0.
        MqttClient subscriber = createClient("sub-retain-qos");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("retain/qos", 0, (topic, msg) -> received.set(msg));

        await().atMost(5, SECONDS).until(() -> received.get() != null);
        assertThat(received.get().getQos()).isEqualTo(0); // downgraded to subscription QoS
    }

}
