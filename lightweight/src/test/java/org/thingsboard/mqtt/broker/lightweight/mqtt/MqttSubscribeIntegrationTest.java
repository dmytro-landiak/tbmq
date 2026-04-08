package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT SUBSCRIBE / UNSUBSCRIBE / SUBACK — PROTO-03.
 *
 * <p>Phase 3 (Plan 02): wildcard subscriptions are now fully delivered via the trie-backed
 * dispatch pipeline. Previously Phase 2 tests asserted no delivery for wildcards (D-06).
 * Those tests are updated here to assert correct delivery (D-07 activated in Phase 3).
 */
class MqttSubscribeIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testSubscribe_exactTopic_receivesMessages() throws Exception {
        MqttClient subscriber = createClient("sub-exact-1");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("test/topic", 0, (topic, msg) -> received.set(msg.getPayload()));

        MqttClient publisher = createClient("pub-exact-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("test/topic", "hello".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("hello");
    }

    @Test
    void testSubscribe_multipleTopics_subackContainsAllQos() throws MqttException {
        MqttClient client = createClient("sub-multi-1");
        client.connect(defaultConnectOptions());
        // Subscribe to 2 topics — Paho throws MqttException if SUBACK return codes indicate failure
        client.subscribe(new String[]{"topic/a", "topic/b"}, new int[]{0, 1});
        // If we get here without exception, SUBACK was received successfully with all grants
    }

    @Test
    void testUnsubscribe_stopsReceivingMessages() throws Exception {
        MqttClient subscriber = createClient("sub-unsub-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("test/unsub", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-unsub-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("test/unsub", "msg1".getBytes(), 0, false);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);

        subscriber.unsubscribe("test/unsub");
        Thread.sleep(300); // allow UNSUBACK to propagate

        publisher.publish("test/unsub", "msg2".getBytes(), 0, false);
        Thread.sleep(1000); // wait to confirm no delivery
        assertThat(count.get()).isEqualTo(1); // msg2 should NOT have been received
    }

    @Test
    void testSubscribe_wildcardPlus_deliversMessages() throws Exception {
        // Phase 3 (D-07 activated): wildcard + subscription delivers matching messages via trie
        MqttClient subscriber = createClient("sub-wild-plus-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("test/+", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-wild-plus-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("test/foo", "hello".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void testSubscribe_wildcardHash_deliversMessages() throws Exception {
        // Phase 3 (D-07 activated): wildcard # subscription delivers matching messages via trie
        MqttClient subscriber = createClient("sub-wild-hash-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("sensor/#", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-wild-hash-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("sensor/temperature", "25".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);
        assertThat(count.get()).isEqualTo(1);
    }

}
