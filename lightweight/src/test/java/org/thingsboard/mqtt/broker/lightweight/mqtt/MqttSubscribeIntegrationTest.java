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
 * <p>Phase 3: wildcard subscriptions are fully delivered via the trie-backed dispatch pipeline.
 * Wildcard + and # filters match and deliver messages. $SYS/ topics are excluded from wildcard
 * matching per MQTT spec section 4.7.2 — only explicit $SYS/ subscriptions receive those messages.
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

    @Test
    void testSubscribe_hashWildcard_doesNotMatchSysTopics() throws Exception {
        // Per D-06/MQTT spec 4.7.2: wildcard # must NOT match topics starting with $
        MqttClient subscriber = createClient("sub-sys-hash-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("#", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-sys-1");
        publisher.connect(defaultConnectOptions());
        // Publish to a $SYS topic — should NOT be delivered to # subscriber
        publisher.publish("$SYS/broker/uptime", "12345".getBytes(), 0, false);
        Thread.sleep(1000);
        assertThat(count.get()).isEqualTo(0); // $SYS/ excluded from # wildcard per spec
    }

    @Test
    void testSubscribe_plusWildcard_doesNotMatchSysTopics() throws Exception {
        // Per D-06/MQTT spec 4.7.2: wildcard + must NOT match topics starting with $
        MqttClient subscriber = createClient("sub-sys-plus-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("+/broker/uptime", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-sys-2");
        publisher.connect(defaultConnectOptions());
        publisher.publish("$SYS/broker/uptime", "12345".getBytes(), 0, false);
        Thread.sleep(1000);
        assertThat(count.get()).isEqualTo(0); // $SYS/ excluded from + wildcard
    }

    @Test
    void testSubscribe_explicitSysTopic_delivers() throws Exception {
        // Explicit $SYS/ subscription SHOULD match
        MqttClient subscriber = createClient("sub-sys-explicit-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("$SYS/broker/+", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-sys-3");
        publisher.connect(defaultConnectOptions());
        publisher.publish("$SYS/broker/uptime", "12345".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void testSubscribe_wildcardMultiLevel_deliversDeepTopics() throws Exception {
        // # should match any depth beyond the prefix
        MqttClient subscriber = createClient("sub-deep-hash-1");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("a/#", 0, (t, m) -> count.incrementAndGet());

        MqttClient publisher = createClient("pub-deep-1");
        publisher.connect(defaultConnectOptions());
        publisher.publish("a/b/c/d", "deep".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 1);
        assertThat(count.get()).isEqualTo(1);
    }

}
