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
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT 5.0 properties — PROTO-09.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>User properties forwarded from publisher to subscriber</li>
 *   <li>Payload format indicator forwarded</li>
 *   <li>Content-Type forwarded</li>
 *   <li>Response topic and correlation data pass-through (D-05)</li>
 *   <li>Message expiry interval enforced on retained messages</li>
 *   <li>Subscription identifier in PUBLISH properties (D-06)</li>
 * </ul>
 */
class Mqtt5PropertiesTest extends AbstractMqtt5IntegrationTest {

    @Test
    void testUserPropertiesForwardedFromPublisherToSubscriber() throws Exception {
        MqttClient subscriber = createV5Client("v5-props-sub-1");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> receivedMsg = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("props/test", 0);

        MqttClient publisher = createV5Client("v5-props-pub-1");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage msg = new MqttMessage("user-prop-payload".getBytes());
        msg.setQos(0);
        MqttProperties props = new MqttProperties();
        props.setUserProperties(List.of(new UserProperty("myKey", "myValue")));
        msg.setProperties(props);
        publisher.publish("props/test", msg);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedMsg.get() != null);

        MqttMessage received = receivedMsg.get();
        assertThat(received).isNotNull();
        assertThat(received.getProperties()).isNotNull();
        List<UserProperty> userProps = received.getProperties().getUserProperties();
        assertThat(userProps).isNotNull();
        assertThat(userProps).isNotEmpty();
        boolean hasExpectedProp = userProps.stream()
                .anyMatch(up -> "myKey".equals(up.getKey()) && "myValue".equals(up.getValue()));
        assertThat(hasExpectedProp).isTrue();
    }

    @Test
    void testPayloadFormatIndicatorForwarded() throws Exception {
        MqttClient subscriber = createV5Client("v5-pfi-sub");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> receivedMsg = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("pfi/test", 0);

        MqttClient publisher = createV5Client("v5-pfi-pub");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage msg = new MqttMessage("{\"value\":1}".getBytes());
        msg.setQos(0);
        MqttProperties props = new MqttProperties();
        props.setPayloadFormat(true); // true = UTF-8 encoded payload (1 in wire format)
        msg.setProperties(props);
        publisher.publish("pfi/test", msg);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedMsg.get() != null);

        MqttMessage received = receivedMsg.get();
        assertThat(received).isNotNull();
        // Payload format indicator should be true (UTF-8) in the received message
        assertThat(received.getProperties()).isNotNull();
        boolean payloadFormat = received.getProperties().getPayloadFormat();
        assertThat(payloadFormat).isTrue();
    }

    @Test
    void testContentTypeForwarded() throws Exception {
        MqttClient subscriber = createV5Client("v5-ct-sub");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> receivedMsg = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("ct/test", 0);

        MqttClient publisher = createV5Client("v5-ct-pub");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage msg = new MqttMessage("{\"type\":\"json\"}".getBytes());
        msg.setQos(0);
        MqttProperties props = new MqttProperties();
        props.setContentType("application/json");
        msg.setProperties(props);
        publisher.publish("ct/test", msg);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedMsg.get() != null);

        MqttMessage received = receivedMsg.get();
        assertThat(received).isNotNull();
        assertThat(received.getProperties()).isNotNull();
        assertThat(received.getProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void testResponseTopicAndCorrelationDataPassThrough() throws Exception {
        // D-05: Response topic and correlation data are passed through by broker
        MqttClient subscriber = createV5Client("v5-rt-sub");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> receivedMsg = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("rt/test", 0);

        MqttClient publisher = createV5Client("v5-rt-pub");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage msg = new MqttMessage("request".getBytes());
        msg.setQos(0);
        MqttProperties props = new MqttProperties();
        props.setResponseTopic("reply/topic");
        props.setCorrelationData("req-123".getBytes());
        msg.setProperties(props);
        publisher.publish("rt/test", msg);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedMsg.get() != null);

        MqttMessage received = receivedMsg.get();
        assertThat(received).isNotNull();
        assertThat(received.getProperties()).isNotNull();
        assertThat(received.getProperties().getResponseTopic()).isEqualTo("reply/topic");
        assertThat(received.getProperties().getCorrelationData()).isEqualTo("req-123".getBytes());
    }

    @Test
    void testMessageExpiryIntervalOnRetainedMessage() throws Exception {
        // Publish a retained message with message expiry interval = 1 second
        MqttClient publisher = createV5Client("v5-expiry-pub");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage retainedMsg = new MqttMessage("expiring".getBytes());
        retainedMsg.setQos(0);
        retainedMsg.setRetained(true);
        MqttProperties retainedProps = new MqttProperties();
        retainedProps.setMessageExpiryInterval(1L); // 1 second expiry
        retainedMsg.setProperties(retainedProps);
        publisher.publish("expiry/v5test", retainedMsg);
        publisher.disconnect(1000);

        // Wait for the message to expire
        Thread.sleep(2000);

        // Subscribe after expiry — no retained message should be delivered
        MqttClient subscriber = createV5Client("v5-expiry-sub");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicInteger receivedCount = new AtomicInteger(0);
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedCount.incrementAndGet();
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("expiry/v5test", 0);

        // Wait a bit to confirm no message arrives
        Thread.sleep(1000);
        assertThat(receivedCount.get()).isEqualTo(0); // expired retained message not delivered

        // Now publish a new retained message with 60s expiry and confirm it IS delivered
        MqttClient publisher2 = createV5Client("v5-expiry-pub2");
        publisher2.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> freshMsg = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                freshMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });

        MqttMessage freshRetained = new MqttMessage("fresh".getBytes());
        freshRetained.setQos(0);
        freshRetained.setRetained(true);
        MqttProperties freshProps = new MqttProperties();
        freshProps.setMessageExpiryInterval(60L); // 60-second expiry
        freshRetained.setProperties(freshProps);
        publisher2.publish("expiry/v5test-fresh", freshRetained);

        // Subscribe to the fresh topic
        subscriber.subscribe("expiry/v5test-fresh", 0);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> freshMsg.get() != null);
        assertThat(new String(freshMsg.get().getPayload())).isEqualTo("fresh");
    }

    @Test
    void testSubscriptionIdentifier() throws Exception {
        // D-06: Subscription identifier set by subscriber is present in PUBLISH properties.
        // Uses MqttAsyncClient directly to access the subscribe() overload that accepts MqttProperties.
        AtomicReference<MqttMessage> receivedMsg = new AtomicReference<>();

        MqttAsyncClient asyncSubscriber = new MqttAsyncClient(brokerUrl(), "v5-subid-sub", new MemoryPersistence());
        v5Clients.add(null); // placeholder — close handled via asyncSubscriber below

        asyncSubscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedMsg.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });

        IMqttToken connectToken = asyncSubscriber.connect(defaultV5ConnectOptions());
        connectToken.waitForCompletion(5000);

        // Subscribe with subscription identifier = 42 using MqttProperties
        MqttProperties subscribeProps = new MqttProperties();
        subscribeProps.setSubscriptionIdentifier(42);
        MqttSubscription[] subscriptions = {new MqttSubscription("subid/v5test", 0)};
        IMqttToken subToken = asyncSubscriber.subscribe(subscriptions, null, (MqttActionListener) null, subscribeProps);
        subToken.waitForCompletion(5000);

        MqttClient publisher = createV5Client("v5-subid-pub");
        publisher.connect(defaultV5ConnectOptions());
        publisher.publish("subid/v5test", "test-msg".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedMsg.get() != null);

        // Cleanup async subscriber
        try {
            asyncSubscriber.disconnect().waitForCompletion(1000);
            asyncSubscriber.close();
        } catch (Exception ignored) {}
        v5Clients.remove(v5Clients.size() - 1); // remove placeholder

        MqttMessage received = receivedMsg.get();
        assertThat(received).isNotNull();
        assertThat(received.getProperties()).isNotNull();
        // Subscription identifier must be included in PUBLISH properties (D-06)
        Integer subId = received.getProperties().getSubscriptionIdentifier();
        assertThat(subId).isNotNull();
        assertThat(subId).isEqualTo(42);
    }

}
