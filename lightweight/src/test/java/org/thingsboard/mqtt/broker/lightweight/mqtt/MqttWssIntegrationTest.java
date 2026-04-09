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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.server.ws.MqttWsServerBootstrap;
import org.thingsboard.mqtt.broker.lightweight.server.wss.MqttWssServerBootstrap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT-over-Secure-WebSocket — TRAN-04.
 *
 * <p>Uses a separate Spring context with TLS and WSS enabled, since WSS requires
 * TLS certificates to be configured.
 *
 * <p>Verifies:
 * <ul>
 *   <li>WSS listener accepts encrypted MQTT connections (TRAN-04)</li>
 *   <li>Publish/subscribe works over wss:// transport (TRAN-04)</li>
 *   <li>WS and WSS transports coexist and share the same dispatch service</li>
 * </ul>
 *
 * <p>Uses port=0 for all listeners to avoid port conflicts with other test contexts.
 * Test CA and server certificates are in {@code src/test/resources/tls/}.
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-wss",
        "tbmq.ws.port=0",
        "tbmq.tls.enabled=true",
        "tbmq.tls.port=0",
        "tbmq.tls.cert-path=classpath:tls/server.pem",
        "tbmq.tls.key-path=classpath:tls/server-key.pem",
        "tbmq.tls.client-auth=NONE",
        "tbmq.wss.enabled=true",
        "tbmq.wss.port=0",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttWssIntegrationTest extends AbstractMqttIntegrationTest {

    @Autowired
    private MqttWssServerBootstrap wssServer;

    @Autowired
    private MqttWsServerBootstrap wsServer;

    /**
     * Returns the WSS broker URL for test clients.
     * CRITICAL: URL MUST include /mqtt path — WebSocketServerProtocolHandler
     * expects the upgrade request at this path.
     */
    private String wssBrokerUrl() {
        return "wss://127.0.0.1:" + wssServer.getLocalPort() + "/mqtt";
    }

    /**
     * Returns the WS broker URL for coexistence test.
     */
    private String wsBrokerUrl() {
        return "ws://127.0.0.1:" + wsServer.getLocalPort() + "/mqtt";
    }

    /**
     * Creates a trusting {@link SSLSocketFactory} that trusts the test CA certificate.
     * Allows Paho WSS clients to connect to the broker using the self-signed test server cert.
     */
    protected SSLSocketFactory createTrustingSocketFactory() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // Load CA certificate from classpath
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        Certificate caCert;
        try (InputStream caStream = getClass().getClassLoader().getResourceAsStream("tls/ca.pem")) {
            assertThat(caStream).as("CA cert classpath resource must exist").isNotNull();
            caCert = certFactory.generateCertificate(caStream);
        }

        // Build a TrustStore with the test CA
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("test-ca", caCert);

        // Create TrustManagerFactory backed by our trust store
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Create SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext.getSocketFactory();
    }

    /**
     * Creates a Paho MqttClient for the WSS endpoint.
     * Registered for auto-cleanup in {@code @AfterEach}.
     */
    private MqttClient createWssClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(wssBrokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    /**
     * Creates a Paho MqttClient for the plain WS endpoint (for coexistence test).
     */
    private MqttClient createWsClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(wsBrokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    /**
     * Returns MqttConnectOptions for WSS connections — includes the trusting socket factory.
     */
    private MqttConnectOptions wssConnectOptions(SSLSocketFactory socketFactory) {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".toCharArray());
        opts.setSocketFactory(socketFactory);
        return opts;
    }

    /**
     * TRAN-04: Connect via wss:// and verify CONNACK accepted.
     */
    @Test
    void testWssConnect_thenConnackAccepted() throws Exception {
        SSLSocketFactory socketFactory = createTrustingSocketFactory();
        MqttClient client = createWssClient("wss-connect-1");

        client.connect(wssConnectOptions(socketFactory));

        assertThat(client.isConnected()).isTrue();
    }

    /**
     * TRAN-04: Publish and subscribe over wss:// — message is delivered.
     */
    @Test
    void testWssPublishSubscribe_thenMessageDelivered() throws Exception {
        SSLSocketFactory socketFactory = createTrustingSocketFactory();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        AtomicReference<String> receivedTopic = new AtomicReference<>();

        MqttClient subscriber = createWssClient("wss-sub-1");
        subscriber.connect(wssConnectOptions(socketFactory));
        subscriber.subscribe("wss/test/topic", 1, (topic, message) -> {
            receivedPayload.set(new String(message.getPayload()));
            receivedTopic.set(topic);
            latch.countDown();
        });

        MqttClient publisher = createWssClient("wss-pub-1");
        publisher.connect(wssConnectOptions(socketFactory));
        MqttMessage msg = new MqttMessage("hello-wss".getBytes());
        msg.setQos(1);
        publisher.publish("wss/test/topic", msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPayload.get()).isEqualTo("hello-wss");
        assertThat(receivedTopic.get()).isEqualTo("wss/test/topic");
    }

    /**
     * TRAN-04: WSS and WS transports coexist — WSS publisher delivers to WS subscriber.
     *
     * <p>Verifies that all WebSocket transports share the same in-process dispatch service.
     */
    @Test
    void testWssAndWsCoexist_thenBothWork() throws Exception {
        SSLSocketFactory socketFactory = createTrustingSocketFactory();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // WS subscriber
        MqttClient wsSubscriber = createWsClient("ws-coexist-wss-1");
        wsSubscriber.connect(defaultConnectOptions());
        wsSubscriber.subscribe("wss-coexist/topic", 1, (topic, message) -> {
            receivedPayload.set(new String(message.getPayload()));
            latch.countDown();
        });

        // WSS publisher
        MqttClient wssPublisher = createWssClient("wss-coexist-1");
        wssPublisher.connect(wssConnectOptions(socketFactory));
        MqttMessage msg = new MqttMessage("from-wss-to-ws".getBytes());
        msg.setQos(1);
        wssPublisher.publish("wss-coexist/topic", msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPayload.get()).isEqualTo("from-wss-to-ws");
    }

}
