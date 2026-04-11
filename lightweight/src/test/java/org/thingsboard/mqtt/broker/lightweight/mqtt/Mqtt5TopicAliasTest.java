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
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT 5.0 topic aliases — PROTO-09.
 *
 * <p>The broker supports:
 * <ul>
 *   <li><b>Inbound topic alias resolution</b>: clients may send a PUBLISH with
 *       topic alias + topic name (first occurrence), then subsequent PUBLISHes with
 *       alias only. The broker resolves the alias to the topic name.</li>
 *   <li><b>Server-side (outbound) topic alias allocation</b>: the broker may assign
 *       topic aliases on outbound PUBLISH messages to reduce bandwidth (D-12/D-14).</li>
 * </ul>
 *
 * <p>Note: Paho MQTT v5 client 1.2.5 does not expose direct control over topic alias
 * assignment in sent PUBLISH packets — the client library handles alias assignment
 * internally. Therefore, these tests verify that:
 * <ul>
 *   <li>Repeated publishes to the same long topic complete correctly (broker alias infra is transparent)</li>
 *   <li>Messages are delivered with correct topic name regardless of alias usage</li>
 *   <li>Server-side topic aliases in broker-to-client PUBLISH do not corrupt delivery</li>
 * </ul>
 */
class Mqtt5TopicAliasTest extends AbstractMqtt5IntegrationTest {

    /** A long topic name that benefits from aliasing (> minTopicAliasLength=5 chars). */
    private static final String LONG_TOPIC = "alias/long-topic-name-for-testing-purposes";

    @Test
    void testRepeatedPublishSameTopic_allDelivered() throws Exception {
        // Validates that the topic alias infrastructure does not break normal message flow.
        // Subscriber subscribes to the long topic, publisher publishes 5 messages.
        // All 5 should be received with the correct topic name.
        MqttClient subscriber = createV5Client("v5-alias-sub-repeat");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicInteger count = new AtomicInteger(0);
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                count.incrementAndGet();
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe(LONG_TOPIC, 0);

        MqttClient publisher = createV5Client("v5-alias-pub-repeat");
        publisher.connect(defaultV5ConnectOptions());

        // Publish 5 messages — Paho may use topic alias after first publish
        for (int i = 0; i < 5; i++) {
            publisher.publish(LONG_TOPIC, ("msg-" + i).getBytes(), 0, false);
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 5);
        assertThat(count.get()).isEqualTo(5);
    }

    @Test
    void testTopicAliasDoesNotAffectTopicNameInDelivery() throws Exception {
        // Validates that the topic name received by the subscriber matches the published topic,
        // even when the broker uses server-side topic aliases on the outbound PUBLISH.
        MqttClient subscriber = createV5Client("v5-alias-sub-topic");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<String> receivedTopic = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedTopic.set(topic);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe(LONG_TOPIC, 0);

        MqttClient publisher = createV5Client("v5-alias-pub-topic");
        publisher.connect(defaultV5ConnectOptions());
        publisher.publish(LONG_TOPIC, "topic-check".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedTopic.get() != null);
        // Paho reconstructs the topic name from alias — subscriber always sees the original topic
        assertThat(receivedTopic.get()).isEqualTo(LONG_TOPIC);
    }

    @Test
    void testServerSideTopicAliasTransparentToSubscriber() throws Exception {
        // Publisher sends 3 messages to long topic, subscriber receives all 3 correctly.
        // This verifies that server-side alias assignment (D-12) is transparent to the subscriber
        // — Paho client handles alias resolution internally.
        MqttClient subscriber = createV5Client("v5-alias-sub-server");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<String> lastTopic = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                count.incrementAndGet();
                lastTopic.set(topic);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        // Subscribe to a long topic so the broker may allocate a server-side alias (minLength=5)
        String testTopic = "alias/server-side-alias-allocation-test";
        subscriber.subscribe(testTopic, 0);

        MqttClient publisher = createV5Client("v5-alias-pub-server");
        publisher.connect(defaultV5ConnectOptions());
        // Publish 3 messages — after first delivery the broker will use alias for subsequent ones
        for (int i = 0; i < 3; i++) {
            publisher.publish(testTopic, ("server-alias-msg-" + i).getBytes(), 0, false);
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 3);
        assertThat(count.get()).isEqualTo(3);
        // Topic name must be correctly resolved even when delivered via alias
        assertThat(lastTopic.get()).isEqualTo(testTopic);
    }

}
