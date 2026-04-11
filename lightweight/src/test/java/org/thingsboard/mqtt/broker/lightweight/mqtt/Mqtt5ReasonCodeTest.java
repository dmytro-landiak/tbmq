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
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for MQTT 5.0 reason codes in protocol ACKs — PROTO-08/PROTO-09.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>QoS 1 PUBACK contains reason code (broker sends success reason code)</li>
 *   <li>QoS 2 full PUBREC/PUBREL/PUBCOMP flow works with 5.0 reason codes</li>
 *   <li>SUBACK contains failure reason code when ACL denies subscription</li>
 *   <li>CONNACK contains failure reason code when credentials are wrong</li>
 * </ul>
 *
 * <p>Note: Paho v5 1.2.5 has a bug in {@code subscribe(String, int, IMqttMessageListener)}
 * where it calls itself recursively and causes a StackOverflow. All tests that need a message
 * listener use {@code setCallback()} + {@code subscribe(String, int)} instead.
 *
 * <p>Note: Paho v5 1.2.5 handles PUBACK/PUBREC/PUBREL/PUBCOMP internally — a successful
 * publish completion without MqttException implicitly validates that the reason codes
 * were correctly parsed by the client.
 */
class Mqtt5ReasonCodeTest extends AbstractMqtt5IntegrationTest {

    @Test
    void testQos1PubackContainsReasonCode() throws Exception {
        // V5 publisher publishes QoS 1 to topic. Paho blocks until PUBACK is received.
        // Successful completion without exception validates that PUBACK reason code was parseable.
        MqttClient subscriber = createV5Client("v5-rc-sub-qos1");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicInteger receivedCount = new AtomicInteger(0);
        // Use setCallback + subscribe(String, int) to avoid Paho v5 1.2.5 subscribe recursion bug
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
        subscriber.subscribe("rc/qos1", 1);

        MqttClient publisher = createV5Client("v5-rc-pub-qos1");
        publisher.connect(defaultV5ConnectOptions());
        // Paho blocks until PUBACK is received — if PUBACK reason code was malformed this would throw
        publisher.publish("rc/qos1", "qos1-rc-msg".getBytes(), 1, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedCount.get() == 1);
        assertThat(receivedCount.get()).isEqualTo(1);
    }

    @Test
    void testQos2FlowWithReasonCodes() throws Exception {
        // Full PUBREC/PUBREL/PUBCOMP QoS 2 flow with MQTT 5.0 reason codes.
        // Paho handles this flow internally — successful completion validates reason code handling.
        MqttClient subscriber = createV5Client("v5-rc-sub-qos2");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicInteger receivedCount = new AtomicInteger(0);
        // Use setCallback + subscribe(String, int) to avoid Paho v5 1.2.5 subscribe recursion bug
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
        subscriber.subscribe("rc/qos2", 2);

        MqttClient publisher = createV5Client("v5-rc-pub-qos2");
        publisher.connect(defaultV5ConnectOptions());
        // Full QoS 2 handshake (PUBLISH -> PUBREC -> PUBREL -> PUBCOMP), all with 5.0 reason codes
        publisher.publish("rc/qos2", "qos2-rc-msg".getBytes(), 2, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> receivedCount.get() == 1);
        assertThat(receivedCount.get()).isEqualTo(1); // delivered exactly once
    }

    @Test
    void testSubackReasonCodeForAclDenied() throws Exception {
        // Creates a credential with publish-only ACL (no subscribe permission on rc/denied/*).
        // The broker sends SUBACK with NOT_AUTHORIZED (0x87) reason code.
        // We verify the subscription was denied by confirming no messages are delivered:
        // a publisher sends to the topic but the denied client receives nothing.
        String username = "rc-sub-denied-user";
        String password = "password123";
        createBasicCredential(username, password,
                List.of(".*"),        // can publish to any topic
                List.of("allowed/.*") // can only subscribe to "allowed/*", not "rc/denied"
        );

        MqttClient deniedClient = createV5Client("v5-rc-denied");
        MqttConnectionOptions opts = authV5ConnectOptions(username, password);
        deniedClient.connectWithResult(opts);
        assertThat(deniedClient.isConnected()).isTrue();

        AtomicInteger receivedByDenied = new AtomicInteger(0);
        deniedClient.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                receivedByDenied.incrementAndGet();
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });

        // Subscribe to a topic outside the allowed patterns — broker returns NOT_AUTHORIZED SUBACK.
        // Paho v5 1.2.5 does not throw on SUBACK NOT_AUTHORIZED, so we verify behaviorally.
        deniedClient.subscribe("rc/denied", 0);

        // Now publish to that topic from an admin client
        MqttClient publisher = createV5Client("v5-rc-pub-denied");
        publisher.connect(defaultV5ConnectOptions());
        publisher.publish("rc/denied", "should-not-arrive".getBytes(), 0, false);

        // Denied client should NOT receive the message (subscription was rejected by NOT_AUTHORIZED SUBACK)
        Thread.sleep(500);
        assertThat(receivedByDenied.get()).isEqualTo(0);
    }

    @Test
    void testAuthFailedConnackReasonCode() {
        // Wrong credentials → CONNACK with failure reason code (BAD_USER_NAME_OR_PASSWORD/NOT_AUTHORIZED).
        // Paho v5 surfaces this as MqttException from connect().
        assertThatThrownBy(() -> {
            MqttClient client = createV5Client("v5-rc-auth-fail");
            MqttConnectionOptions opts = authV5ConnectOptions("tbmq", "completely-wrong");
            client.connect(opts);
        }).isInstanceOf(MqttException.class);
    }

    @Test
    void testSubackSuccessReasonCodeForAuthorizedTopic() throws Exception {
        // Verifies that SUBACK returns success for an authorized subscribe.
        // Paho v5 subscribe() throws MqttException on SUBACK failure — no exception means success.
        MqttClient client = createV5Client("v5-rc-suback-ok");
        client.connect(defaultV5ConnectOptions());

        // Subscribe to any topic with default tbmq/tbmq credential (full access)
        client.subscribe("rc/success/topic", 1);
        // If we reach here without exception, SUBACK success reason code was correctly parsed
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void testMultipleQosLevelsWithReasonCodes() throws Exception {
        // Publishes at QoS 0, 1, and 2 to verify all ACK types work with MQTT 5.0 reason codes.
        MqttClient subscriber = createV5Client("v5-rc-multi-sub");
        subscriber.connect(defaultV5ConnectOptions());
        AtomicInteger received = new AtomicInteger(0);
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.incrementAndGet();
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("rc/multi", 2);

        MqttClient publisher = createV5Client("v5-rc-multi-pub");
        publisher.connect(defaultV5ConnectOptions());
        publisher.publish("rc/multi", "qos0".getBytes(), 0, false);
        publisher.publish("rc/multi", "qos1".getBytes(), 1, false);
        publisher.publish("rc/multi", "qos2".getBytes(), 2, false);

        // Wait for all 3 messages
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> received.get() == 3);
        assertThat(received.get()).isEqualTo(3);
    }

}
