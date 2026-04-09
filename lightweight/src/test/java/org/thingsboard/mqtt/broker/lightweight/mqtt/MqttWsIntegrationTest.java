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

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.mqtt.broker.lightweight.server.ws.MqttWsServerBootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT-over-WebSocket — TRAN-03 and TRAN-05.
 *
 * <p>Verifies:
 * <ul>
 *   <li>WS listener accepts MQTT connections (TRAN-03)</li>
 *   <li>Publish/subscribe works over WS transport (TRAN-03)</li>
 *   <li>WS and TCP transports coexist and share the same dispatch service (TRAN-03)</li>
 *   <li>Sec-WebSocket-Protocol header negotiation works — Paho connect() validates it (TRAN-05)</li>
 * </ul>
 *
 * <p>Inherits the base {@code @SpringBootTest} context from {@link AbstractMqttIntegrationTest}
 * which includes {@code tbmq.ws.port=0} for a random WS port.
 */
class MqttWsIntegrationTest extends AbstractMqttIntegrationTest {

    @Autowired
    private MqttWsServerBootstrap wsServer;

    /**
     * Returns the WS broker URL for test clients.
     * CRITICAL: URL MUST include /mqtt path — WebSocketServerProtocolHandler
     * expects the upgrade request at this path; without it, HTTP 400 is returned.
     */
    private String wsBrokerUrl() {
        return "ws://127.0.0.1:" + wsServer.getLocalPort() + "/mqtt";
    }

    /**
     * Creates a Paho MqttClient connected to the WS endpoint.
     * Registered for auto-cleanup in {@code @AfterEach}.
     */
    private MqttClient createWsClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(wsBrokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    /**
     * TRAN-03 + TRAN-05: Connect via ws:// and verify CONNACK accepted.
     *
     * <p>Paho's WebSocket handshake validates the Sec-WebSocket-Protocol response header.
     * If the broker does not echo back the negotiated subprotocol (e.g., "mqtt"), Paho
     * throws a HandshakeFailedException before CONNACK is processed. A successful
     * {@code connect()} therefore implicitly verifies TRAN-05.
     */
    @Test
    void testWsConnect_thenConnackAccepted() throws Exception {
        MqttClient client = createWsClient("ws-connect-1");

        client.connect(defaultConnectOptions());

        assertThat(client.isConnected()).isTrue();
    }

    /**
     * TRAN-03: Publish and subscribe over ws:// — message is delivered.
     */
    @Test
    void testWsPublishSubscribe_thenMessageDelivered() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        AtomicReference<String> receivedTopic = new AtomicReference<>();

        MqttClient subscriber = createWsClient("ws-sub-1");
        subscriber.connect(defaultConnectOptions());
        subscriber.subscribe("ws/test/topic", 1, (topic, message) -> {
            receivedPayload.set(new String(message.getPayload()));
            receivedTopic.set(topic);
            latch.countDown();
        });

        MqttClient publisher = createWsClient("ws-pub-1");
        publisher.connect(defaultConnectOptions());
        MqttMessage msg = new MqttMessage("hello-ws".getBytes());
        msg.setQos(1);
        publisher.publish("ws/test/topic", msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPayload.get()).isEqualTo("hello-ws");
        assertThat(receivedTopic.get()).isEqualTo("ws/test/topic");
    }

    /**
     * TRAN-03: TCP and WS transports coexist — WS publisher delivers to TCP subscriber.
     *
     * <p>Verifies that both transports feed into the same in-process dispatch service.
     */
    @Test
    void testWsAndTcpCoexist_thenBothWork() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // TCP subscriber
        MqttClient tcpSubscriber = createClient("tcp-coexist-1");
        tcpSubscriber.connect(defaultConnectOptions());
        tcpSubscriber.subscribe("coexist/topic", 1, (topic, message) -> {
            receivedPayload.set(new String(message.getPayload()));
            latch.countDown();
        });

        // WS publisher
        MqttClient wsPublisher = createWsClient("ws-coexist-1");
        wsPublisher.connect(defaultConnectOptions());
        MqttMessage msg = new MqttMessage("cross-transport".getBytes());
        msg.setQos(1);
        wsPublisher.publish("coexist/topic", msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPayload.get()).isEqualTo("cross-transport");
    }

}
