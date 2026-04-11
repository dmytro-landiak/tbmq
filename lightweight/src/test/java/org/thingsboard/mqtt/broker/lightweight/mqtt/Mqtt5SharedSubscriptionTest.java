/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT shared subscriptions — PROTO-10.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Shared subscription delivers each message to exactly one group member</li>
 *   <li>Round-robin distribution across group members (D-09)</li>
 *   <li>Shared subscriptions work for MQTT 3.1.1 clients (D-11)</li>
 *   <li>Non-shared and shared subscriptions coexist correctly</li>
 *   <li>Retained messages are NOT delivered to shared subscriptions (per MQTT 5.0 spec)</li>
 * </ul>
 *
 * <p>Topic format: {@code $share/<groupName>/<topicFilter>}
 */
class Mqtt5SharedSubscriptionTest extends AbstractMqtt5IntegrationTest {

    @Test
    void testSharedSubscriptionDeliversToExactlyOneMember() throws Exception {
        // 3 subscribers in the same share group — each message goes to exactly one.
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        AtomicInteger count3 = new AtomicInteger(0);

        org.eclipse.paho.mqttv5.client.MqttClient sub1 = createV5Client("v5-shared-sub1-a");
        org.eclipse.paho.mqttv5.client.MqttClient sub2 = createV5Client("v5-shared-sub2-a");
        org.eclipse.paho.mqttv5.client.MqttClient sub3 = createV5Client("v5-shared-sub3-a");

        sub1.connect(defaultV5ConnectOptions());
        sub2.connect(defaultV5ConnectOptions());
        sub3.connect(defaultV5ConnectOptions());

        sub1.setCallback(makeCallback(count1));
        sub2.setCallback(makeCallback(count2));
        sub3.setCallback(makeCallback(count3));

        sub1.subscribe("$share/groupA/shared/topic/a", 0);
        sub2.subscribe("$share/groupA/shared/topic/a", 0);
        sub3.subscribe("$share/groupA/shared/topic/a", 0);

        // Allow subscriptions to register
        Thread.sleep(300);

        org.eclipse.paho.mqttv5.client.MqttClient publisher = createV5Client("v5-shared-pub-a");
        publisher.connect(defaultV5ConnectOptions());

        for (int i = 0; i < 10; i++) {
            publisher.publish("shared/topic/a", ("msg-" + i).getBytes(), 0, false);
        }

        // Wait until all 10 messages are distributed
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> (count1.get() + count2.get() + count3.get()) == 10);

        int total = count1.get() + count2.get() + count3.get();
        assertThat(total).isEqualTo(10); // every message delivered to exactly one subscriber
        // Each subscriber should receive at least 1 (round-robin with 3 subscribers and 10 messages)
        assertThat(count1.get()).isGreaterThan(0);
        assertThat(count2.get()).isGreaterThan(0);
        assertThat(count3.get()).isGreaterThan(0);
    }

    @Test
    void testSharedSubscriptionWithMqtt311Client() throws Exception {
        // D-11: Shared subscriptions work for MQTT 3.1.1 clients.
        // Note: Paho v3 routes incoming messages to inline listeners by matching the PUBLISH
        // topic name against the subscribed topic filter. Since the broker delivers to the
        // actual topic (shared311/topic), NOT the $share/group/... filter, the inline listener
        // would never fire. We use setCallback() instead which receives ALL incoming messages.
        MqttClient v3Sub = createClient("v3-shared-sub");
        v3Sub.connect(defaultConnectOptions());

        AtomicInteger received = new AtomicInteger(0);
        v3Sub.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {}
            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                received.incrementAndGet();
            }
            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {}
        });
        v3Sub.subscribe("$share/group311/shared311/topic", 0);

        Thread.sleep(300); // allow subscription registration

        MqttClient v3Pub = createClient("v3-shared-pub");
        v3Pub.connect(defaultConnectOptions());

        for (int i = 0; i < 5; i++) {
            v3Pub.publish("shared311/topic", ("v3-msg-" + i).getBytes(), 0, false);
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() == 5);
        assertThat(received.get()).isEqualTo(5);
    }

    @Test
    void testSharedAndNonSharedSubscribersReceiveMessages() throws Exception {
        // Client A: shared subscriber — receives its share (round-robin, only group member → all)
        // Client B: non-shared subscriber — receives ALL messages
        AtomicInteger sharedCount = new AtomicInteger(0);
        AtomicInteger normalCount = new AtomicInteger(0);

        org.eclipse.paho.mqttv5.client.MqttClient sharedSub = createV5Client("v5-mixed-shared");
        org.eclipse.paho.mqttv5.client.MqttClient normalSub = createV5Client("v5-mixed-normal");

        sharedSub.connect(defaultV5ConnectOptions());
        normalSub.connect(defaultV5ConnectOptions());

        sharedSub.setCallback(makeCallback(sharedCount));
        normalSub.setCallback(makeCallback(normalCount));

        sharedSub.subscribe("$share/mixedGroup/mixed/coexist", 0);
        normalSub.subscribe("mixed/coexist", 0);

        Thread.sleep(300);

        org.eclipse.paho.mqttv5.client.MqttClient publisher = createV5Client("v5-mixed-pub");
        publisher.connect(defaultV5ConnectOptions());

        for (int i = 0; i < 5; i++) {
            publisher.publish("mixed/coexist", ("coexist-" + i).getBytes(), 0, false);
        }

        // Non-shared subscriber must receive all 5; shared (sole group member) also receives all 5
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> normalCount.get() == 5 && sharedCount.get() == 5);

        assertThat(normalCount.get()).isEqualTo(5);
        assertThat(sharedCount.get()).isEqualTo(5);
    }

    @Test
    void testSharedSubscriptionNoRetainedMessage() throws Exception {
        // Per MQTT 5.0 spec: retained messages are NOT delivered to shared subscriptions.
        // Publish a retained message first, then subscribe via $share — no retained delivery expected.
        org.eclipse.paho.mqttv5.client.MqttClient publisher = createV5Client("v5-shared-ret-pub");
        publisher.connect(defaultV5ConnectOptions());
        publisher.publish("noretain/shared-test", "retained".getBytes(), 0, true); // retain=true
        publisher.disconnect(1000);

        Thread.sleep(300);

        // Subscribe via shared subscription — retained message must NOT be delivered
        AtomicInteger received = new AtomicInteger(0);
        org.eclipse.paho.mqttv5.client.MqttClient sub = createV5Client("v5-shared-ret-sub");
        sub.connect(defaultV5ConnectOptions());
        sub.setCallback(makeCallback(received));
        sub.subscribe("$share/noretainGroup/noretain/shared-test", 0);

        Thread.sleep(1500); // wait to confirm no retained message arrives
        assertThat(received.get()).isEqualTo(0);
    }

    @Test
    void testSharedSubscriptionRoundRobin() throws Exception {
        // 2 subscribers in the same group, 6 messages published.
        // With round-robin (D-09), each subscriber should receive exactly 3.
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        org.eclipse.paho.mqttv5.client.MqttClient sub1 = createV5Client("v5-rr-sub1");
        org.eclipse.paho.mqttv5.client.MqttClient sub2 = createV5Client("v5-rr-sub2");

        sub1.connect(defaultV5ConnectOptions());
        sub2.connect(defaultV5ConnectOptions());

        sub1.setCallback(makeCallback(count1));
        sub2.setCallback(makeCallback(count2));

        sub1.subscribe("$share/rrGroup/roundrobin/topic", 0);
        sub2.subscribe("$share/rrGroup/roundrobin/topic", 0);

        Thread.sleep(300); // allow subscription registration

        org.eclipse.paho.mqttv5.client.MqttClient publisher = createV5Client("v5-rr-pub");
        publisher.connect(defaultV5ConnectOptions());

        for (int i = 0; i < 6; i++) {
            publisher.publish("roundrobin/topic", ("rr-" + i).getBytes(), 0, false);
        }

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> (count1.get() + count2.get()) == 6);

        assertThat(count1.get() + count2.get()).isEqualTo(6);
        // Round-robin with 2 subscribers and 6 messages: each gets 3
        assertThat(count1.get()).isEqualTo(3);
        assertThat(count2.get()).isEqualTo(3);
    }

    /**
     * Creates a simple callback that increments the given counter on each message arrival.
     */
    private MqttCallback makeCallback(AtomicInteger counter) {
        return new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                counter.incrementAndGet();
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        };
    }

}
