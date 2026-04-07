package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for MQTT client takeover (duplicate clientId) — PROTO-11.
 *
 * <p>Per MQTT 3.1.1 spec section 3.1.4: if a client connects using a clientId that is already
 * connected, the broker MUST disconnect the existing connection. The new connection takes over.
 */
class MqttClientTakeoverTest extends AbstractMqttIntegrationTest {

    @Test
    void testClientTakeover_newConnectionReplacesOld() throws Exception {
        // First client connects.
        MqttClient client1 = createClient("takeover-client");
        client1.connect(defaultConnectOptions());
        assertThat(client1.isConnected()).isTrue();

        // Second client connects with the same clientId — should take over.
        MqttClient client2 = new MqttClient(brokerUrl(), "takeover-client", new MemoryPersistence());
        clients.add(client2);
        client2.connect(defaultConnectOptions());
        assertThat(client2.isConnected()).isTrue();

        // client1 should be disconnected by the broker.
        await().atMost(5, SECONDS).until(() -> !client1.isConnected());
    }

    @Test
    void testClientTakeover_lwtNotDeliveredForDisplacedSession() throws Exception {
        // Subscriber listens on the LWT topic.
        MqttClient subscriber = createClient("sub-takeover-lwt");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("takeover/lwt", 0, (topic, msg) -> count.incrementAndGet());

        // First client connects with a will message.
        MqttClient client1 = createClient("takeover-lwt-client");
        MqttConnectOptions opts1 = defaultConnectOptions();
        opts1.setWill("takeover/lwt", "should-not-fire".getBytes(), 0, false);
        client1.connect(opts1);

        // Second client with same clientId takes over — LWT for client1 MUST NOT fire.
        MqttClient client2 = new MqttClient(brokerUrl(), "takeover-lwt-client", new MemoryPersistence());
        clients.add(client2);
        client2.connect(defaultConnectOptions());

        Thread.sleep(2000);
        assertThat(count.get()).isEqualTo(0); // LWT NOT delivered on takeover
        client2.disconnect();
    }

    @Test
    void testClientTakeover_newSessionFunctional() throws Exception {
        // First client connects.
        MqttClient client1 = createClient("takeover-func");
        client1.connect(defaultConnectOptions());

        // Second client with same clientId takes over.
        MqttClient client2 = new MqttClient(brokerUrl(), "takeover-func", new MemoryPersistence());
        clients.add(client2);
        client2.connect(defaultConnectOptions());

        // Wait for takeover to complete.
        await().atMost(5, SECONDS).until(() -> !client1.isConnected());

        // Subscriber listens.
        MqttClient subscriber = createClient("sub-takeover-func");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("takeover/func", 0, (topic, msg) -> received.set(msg));

        // Verify client2 (new session) can publish successfully.
        MqttMessage msg = new MqttMessage("from-new-session".getBytes());
        msg.setQos(0);
        client2.publish("takeover/func", msg);

        await().atMost(5, SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get().getPayload())).isEqualTo("from-new-session");

        client2.disconnect();
    }

}
