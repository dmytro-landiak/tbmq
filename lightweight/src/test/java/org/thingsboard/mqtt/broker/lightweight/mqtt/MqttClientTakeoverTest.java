package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
    void testNewConnectionSurvivesRapidTakeoverRace() throws Exception {
        // Stress-fire 20 takeovers in rapid succession on the same clientId.
        // Without the fix, the displaced-session race window may close the new connection
        // (the OLD handler's channelInactive sends SessionCloseMsg under stale state).
        String clientId = "takeover-race-" + UUID.randomUUID();

        MqttClient lastClient = null;
        for (int i = 0; i < 20; i++) {
            MqttClient c = createClient(clientId);
            c.connect(defaultConnectOptions());
            // Do NOT disconnect — the next iteration is a TCP-level takeover.
            lastClient = c;
        }

        // Assert the final client is still connected.
        assertThat(lastClient).isNotNull();
        assertThat(lastClient.isConnected())
                .as("Most-recent client should remain connected after rapid takeover storm")
                .isTrue();

        // Dwell so any delayed SessionCloseMsg-driven teardown has a chance to land.
        Thread.sleep(200);
        assertThat(lastClient.isConnected())
                .as("Most-recent client should still be connected after dwell")
                .isTrue();

        // Round-trip: subscribe + publish to itself, expect delivery.
        CountDownLatch deliveryLatch = new CountDownLatch(1);
        lastClient.subscribe("takeover/race/" + clientId, (topic, message) -> deliveryLatch.countDown());
        lastClient.publish("takeover/race/" + clientId, "hello".getBytes(), 1, false);
        assertThat(deliveryLatch.await(5, SECONDS))
                .as("Final client should remain operational after takeover race")
                .isTrue();
    }

    @Test
    void testNewConnectionSurvivesRapidReconnectAfterDisconnect() throws Exception {
        // Stress-fire 20 clean-disconnect-then-reconnect cycles on the same clientId.
        // Without the sibling fix in processDisconnect, a late channelInactive from the
        // OLD disconnected session could deliver SessionCloseMsg to the FRESH actor for
        // the next iteration's connect (createRootActor returns a fresh actor with the
        // same actorId after the previous ctx.stop), tearing down a connection that
        // should be intact.
        String clientId = "reconnect-race-" + UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            MqttClient c = createClient(clientId);
            c.connect(defaultConnectOptions());
            // Clean disconnect — the next iteration is a fresh connect on the same clientId.
            c.disconnect();
        }

        // Final client connects with the same clientId.
        MqttClient lastClient = createClient(clientId);
        lastClient.connect(defaultConnectOptions());
        assertThat(lastClient.isConnected())
                .as("Final client should connect after rapid disconnect/reconnect storm")
                .isTrue();

        // Dwell so any delayed SessionCloseMsg-driven teardown has a chance to land.
        Thread.sleep(200);
        assertThat(lastClient.isConnected())
                .as("Final client should still be connected after dwell")
                .isTrue();

        // Round-trip: subscribe + publish to itself, expect delivery.
        CountDownLatch deliveryLatch = new CountDownLatch(1);
        lastClient.subscribe("reconnect/race/" + clientId, (topic, message) -> deliveryLatch.countDown());
        lastClient.publish("reconnect/race/" + clientId, "hello".getBytes(), 1, false);
        assertThat(deliveryLatch.await(5, SECONDS))
                .as("Final client should remain operational after reconnect race")
                .isTrue();
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
