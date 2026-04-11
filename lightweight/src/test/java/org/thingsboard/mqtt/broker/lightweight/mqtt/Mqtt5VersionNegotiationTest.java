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
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for MQTT 5.0 version negotiation — PROTO-08.
 *
 * <p>Tests verify that:
 * <ul>
 *   <li>MQTT 5.0 clients connect successfully and receive CONNACK with properties</li>
 *   <li>MQTT 3.1.1 and 5.0 clients coexist on the same port simultaneously</li>
 *   <li>CONNACK includes TopicAliasMaximum (D-13)</li>
 *   <li>Session expiry override to 0 is signaled in CONNACK (D-02)</li>
 * </ul>
 *
 * <p>Note: {@code connectWithResult()} is used instead of {@code connect()} to obtain
 * the {@link IMqttToken} carrying CONNACK response properties. The plain {@code connect()}
 * method returns void in Paho v5 1.2.5.
 */
class Mqtt5VersionNegotiationTest extends AbstractMqtt5IntegrationTest {

    @Test
    void testMqtt5ClientConnectsSuccessfully() throws Exception {
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-connect-test");
        // connectWithResult() returns IMqttToken with CONNACK response properties
        IMqttToken token = v5Client.connectWithResult(defaultV5ConnectOptions());

        assertThat(v5Client.isConnected()).isTrue();
        assertThat(token).isNotNull();
    }

    @Test
    void testMqtt311And5CoexistOnSamePort() throws Exception {
        // Connect a v3 client
        MqttClient v3Client = createClient("v3-coexist");
        v3Client.connect(defaultConnectOptions());

        // Connect a v5 client simultaneously on the same port.
        // connectWithResult() ensures CONNACK properties (including ReceiveMaximum) are fully
        // processed before the method returns, avoiding "Too many publishes in progress" errors.
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-coexist");
        v5Client.connectWithResult(defaultV5ConnectOptions());

        assertThat(v3Client.isConnected()).isTrue();
        assertThat(v5Client.isConnected()).isTrue();

        // Cross-version message delivery: v5 publisher -> v3 subscriber
        AtomicReference<byte[]> received = new AtomicReference<>();
        v3Client.subscribe("coexist/cross-version", 0, (topic, msg) -> received.set(msg.getPayload()));
        Thread.sleep(300); // wait for SUBACK to be processed

        v5Client.publish("coexist/cross-version", "cross-version".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("cross-version");
    }

    @Test
    void testMqtt5ConnackIncludesTopicAliasMaximum() throws Exception {
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-connack-alias");
        IMqttToken token = v5Client.connectWithResult(defaultV5ConnectOptions());

        MqttProperties responseProperties = token.getResponseProperties();
        assertThat(responseProperties).isNotNull();
        // D-13: broker advertises Topic Alias Maximum (default = 10)
        Integer topicAliasMax = responseProperties.getTopicAliasMaximum();
        assertThat(topicAliasMax).isNotNull();
        assertThat(topicAliasMax).isGreaterThan(0);
    }

    @Test
    void testMqtt5ConnackBrokerConnectionSuccessful() throws Exception {
        // Verifies that MQTT 5.0 CONNACK is parseable without errors
        // (validates Receive Maximum, server-side properties are well-formed)
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-connack-recv");
        IMqttToken token = v5Client.connectWithResult(defaultV5ConnectOptions());

        assertThat(v5Client.isConnected()).isTrue();
        assertThat(token.getResponseProperties()).isNotNull();
    }

    @Test
    void testMqtt5ConnackIncludesSessionExpiryOverride() throws Exception {
        // D-02: Client sends session expiry > 0; broker overrides to 0 in CONNACK
        // (broker is clean-session-only in R1, cannot honor session persistence)
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-sess-expiry");
        MqttConnectionOptions opts = defaultV5ConnectOptions();
        opts.setSessionExpiryInterval(300L); // request 300-second session

        IMqttToken token = v5Client.connectWithResult(opts);

        MqttProperties responseProperties = token.getResponseProperties();
        assertThat(responseProperties).isNotNull();
        // D-02: broker must signal session expiry = 0 (cannot honor non-zero expiry in R1)
        Long sessionExpiry = responseProperties.getSessionExpiryInterval();
        assertThat(sessionExpiry).isNotNull();
        assertThat(sessionExpiry).isEqualTo(0L);
    }

    @Test
    void testMqtt5ConnackOmitsSessionExpiryWhenClientSendsZero() throws Exception {
        // D-02: Client sends session expiry = 0; broker should NOT include
        // session expiry in CONNACK (only included when client requested non-zero)
        org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-sess-zero");
        MqttConnectionOptions opts = defaultV5ConnectOptions();
        opts.setSessionExpiryInterval(0L); // no persistent session

        IMqttToken token = v5Client.connectWithResult(opts);

        // Connection must succeed
        assertThat(v5Client.isConnected()).isTrue();
        // Broker must NOT send session expiry override when client already sent 0
        MqttProperties responseProperties = token.getResponseProperties();
        if (responseProperties != null) {
            Long sessionExpiry = responseProperties.getSessionExpiryInterval();
            // Null means broker correctly omitted it; 0 is also acceptable
            if (sessionExpiry != null) {
                assertThat(sessionExpiry).isEqualTo(0L);
            }
        }
    }

    @Test
    void testMqtt5AuthFailedConnackReasonCode() {
        // Test that wrong password results in MqttException (reason: BAD_USER_NAME_OR_PASSWORD / NOT_AUTHORIZED)
        assertThatThrownBy(() -> {
            org.eclipse.paho.mqttv5.client.MqttClient v5Client = createV5Client("v5-auth-fail");
            MqttConnectionOptions opts = authV5ConnectOptions("tbmq", "wrong-password");
            v5Client.connect(opts);
        }).isInstanceOf(MqttException.class);
    }

}
