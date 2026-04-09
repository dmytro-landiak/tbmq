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
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.server.tls.MqttSslServerBootstrap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for MQTT-over-TLS — TRAN-02.
 *
 * <p>Verifies:
 * <ul>
 *   <li>TLS listener starts and accepts encrypted MQTT connections</li>
 *   <li>Client with untrusted server cert fails the TLS handshake</li>
 *   <li>Plain TCP listener coexists with TLS listener on different ports</li>
 * </ul>
 *
 * <p>Uses port=0 for both TCP and TLS to avoid port conflicts with other test contexts.
 * Test CA and server certificates are in {@code src/test/resources/tls/}.
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-tls",
        "tbmq.tls.enabled=true",
        "tbmq.tls.port=0",
        "tbmq.tls.cert-path=classpath:tls/server.pem",
        "tbmq.tls.key-path=classpath:tls/server-key.pem",
        "tbmq.tls.client-auth=NONE",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttTlsIntegrationTest extends AbstractMqttIntegrationTest {

    @Autowired
    private MqttSslServerBootstrap tlsServer;

    /**
     * Returns the TLS broker URL for test clients.
     * Uses the OS-assigned random port to avoid conflicts.
     */
    protected String tlsBrokerUrl() {
        return "ssl://127.0.0.1:" + tlsServer.getLocalPort();
    }

    /**
     * Creates a trusting {@link SSLSocketFactory} that trusts the test CA certificate.
     * Allows Paho clients to connect to the broker using the self-signed test server cert.
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
     * Creates a Paho MqttClient that connects to the TLS endpoint.
     * Registered for auto-cleanup in {@code @AfterEach}.
     */
    protected MqttClient createTlsClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(tlsBrokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    /**
     * Returns MqttConnectOptions with the test CA trust factory and default credentials.
     */
    protected MqttConnectOptions tlsConnectOptions(SSLSocketFactory socketFactory) {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".toCharArray());
        opts.setSocketFactory(socketFactory);
        return opts;
    }

    @Test
    void givenTlsEnabled_whenConnectWithTls_thenConnackAccepted() throws Exception {
        SSLSocketFactory socketFactory = createTrustingSocketFactory();
        MqttClient client = createTlsClient("tls-connect-1");

        client.connect(tlsConnectOptions(socketFactory));

        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void givenTlsEnabled_whenConnectWithPlainTcp_thenTcpStillWorks() throws Exception {
        // Plain TCP should still work alongside TLS
        MqttClient tcpClient = createClient("tls-tcp-coexist-1");
        tcpClient.connect(defaultConnectOptions());

        assertThat(tcpClient.isConnected()).isTrue();
    }

    @Test
    void givenTlsEnabled_whenConnectWithUntrustedCert_thenHandshakeFails() throws Exception {
        // Use default JDK trust store (does not trust our self-signed test CA)
        MqttClient client = new MqttClient(tlsBrokerUrl(), "tls-untrusted-1", new MemoryPersistence());
        clients.add(client);

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".toCharArray());
        // No custom socket factory — JDK default trust store won't trust the test CA

        assertThatThrownBy(() -> client.connect(opts))
                .isInstanceOf(MqttException.class);
        assertThat(client.isConnected()).isFalse();
    }

}
