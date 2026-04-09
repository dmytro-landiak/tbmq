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
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.security.auth.CredentialType;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredential;
import org.thingsboard.mqtt.broker.lightweight.security.auth.PubSubAuthorizationRules;
import org.thingsboard.mqtt.broker.lightweight.security.auth.SslMqttCredentials;
import org.thingsboard.mqtt.broker.lightweight.server.tls.MqttSslServerBootstrap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for mTLS (mutual TLS) client certificate authentication — AUTH-02.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Client with a valid X.509 certificate (CN registered in RocksDB) is authenticated</li>
 *   <li>Client without a certificate fails the TLS handshake (server requires client cert)</li>
 *   <li>Authenticated mTLS client can pub/sub after certificate-based auth</li>
 * </ul>
 *
 * <p>The test PKI uses: CA from {@code tls/ca.pem}, server cert from {@code tls/server.pem},
 * and client cert from {@code tls/client.pem} (CN=test-client).
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-mtls",
        "tbmq.tls.enabled=true",
        "tbmq.tls.port=0",
        "tbmq.tls.cert-path=classpath:tls/server.pem",
        "tbmq.tls.key-path=classpath:tls/server-key.pem",
        "tbmq.tls.client-auth=REQUIRED",
        "tbmq.tls.trust-cert-path=classpath:tls/ca.pem",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttMtlsIntegrationTest extends AbstractMqttIntegrationTest {

    private static final String CLIENT_CN = "test-client";

    @Autowired
    private MqttSslServerBootstrap tlsServer;

    @BeforeEach
    void createSslCredential() throws Exception {
        // Register the test client CN in RocksDB with allow-all ACL
        SslMqttCredentials sslCreds = new SslMqttCredentials(
                Map.of(CLIENT_CN, PubSubAuthorizationRules.defaultInstance()));
        String credentialValue = objectMapper.writeValueAsString(sslCreds);
        LightweightCredential credential = new LightweightCredential(
                CLIENT_CN, CredentialType.SSL, credentialValue);
        credentialService.saveCredential(credential);
        createdCredentialIds.add(CLIENT_CN);
    }

    @AfterEach
    void deleteSslCredential() {
        credentialService.deleteCredential(CLIENT_CN);
        createdCredentialIds.remove(CLIENT_CN);
    }

    /**
     * Returns the TLS broker URL using the OS-assigned port.
     */
    protected String tlsBrokerUrl() {
        return "ssl://127.0.0.1:" + tlsServer.getLocalPort();
    }

    /**
     * Creates an {@link SSLSocketFactory} for mTLS: trusts the test CA and presents the
     * test client certificate + private key during the TLS handshake.
     */
    protected SSLSocketFactory createMtlsSocketFactory() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter();
        JcaPEMKeyConverter keyConverter = new JcaPEMKeyConverter();

        // Load CA cert for TrustManager
        X509Certificate caCert;
        try (InputStream caStream = getClass().getClassLoader().getResourceAsStream("tls/ca.pem");
             PEMParser parser = new PEMParser(new InputStreamReader(caStream))) {
            caCert = certConverter.getCertificate((X509CertificateHolder) parser.readObject());
        }

        // Load client cert
        X509Certificate clientCert;
        try (InputStream certStream = getClass().getClassLoader().getResourceAsStream("tls/client.pem");
             PEMParser parser = new PEMParser(new InputStreamReader(certStream))) {
            clientCert = certConverter.getCertificate((X509CertificateHolder) parser.readObject());
        }

        // Load client private key
        PrivateKey clientKey;
        try (InputStream keyStream = getClass().getClassLoader().getResourceAsStream("tls/client-key.pem");
             PEMParser parser = new PEMParser(new InputStreamReader(keyStream))) {
            Object obj = parser.readObject();
            if (obj instanceof PEMKeyPair) {
                clientKey = keyConverter.getKeyPair((PEMKeyPair) obj).getPrivate();
            } else if (obj instanceof PrivateKeyInfo) {
                clientKey = keyConverter.getPrivateKey((PrivateKeyInfo) obj);
            } else {
                throw new IllegalStateException("Unexpected PEM object type: " + obj.getClass());
            }
        }

        // Build TrustStore with CA cert
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("test-ca", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Build KeyStore with client cert + key
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", clientKey, new char[0], new Certificate[]{clientCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext.getSocketFactory();
    }

    /**
     * Creates a trust-only {@link SSLSocketFactory} (trusts the server CA, no client cert).
     * Used to verify that the server rejects connections without a client cert.
     */
    protected SSLSocketFactory createTrustOnlySocketFactory() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        Certificate caCert;
        try (InputStream caStream = getClass().getClassLoader().getResourceAsStream("tls/ca.pem")) {
            caCert = certFactory.generateCertificate(caStream);
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("test-ca", caCert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext.getSocketFactory();
    }

    protected MqttClient createTlsClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(tlsBrokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    @Test
    void givenMtlsRequired_whenConnectWithValidClientCert_thenAuthenticated() throws Exception {
        SSLSocketFactory socketFactory = createMtlsSocketFactory();

        MqttClient client = createTlsClient("mtls-valid-1");
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setSocketFactory(socketFactory);
        // No username/password — mTLS certificate authentication only

        client.connect(opts);

        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void givenMtlsRequired_whenConnectWithNoClientCert_thenHandshakeFails() throws Exception {
        // Trust-only factory: trusts server cert but provides no client cert
        SSLSocketFactory socketFactory = createTrustOnlySocketFactory();

        MqttClient client = new MqttClient(tlsBrokerUrl(), "mtls-nocert-1", new MemoryPersistence());
        clients.add(client);

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setSocketFactory(socketFactory);

        assertThatThrownBy(() -> client.connect(opts))
                .isInstanceOf(MqttException.class);
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void givenMtlsRequired_whenConnectWithValidCertAndPubSub_thenMessageDelivered() throws Exception {
        SSLSocketFactory socketFactory = createMtlsSocketFactory();

        // Subscribe client
        MqttClient subscriber = createTlsClient("mtls-sub-1");
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setSocketFactory(createMtlsSocketFactory());
        subscriber.connect(opts);

        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("sensor/data", (topic, msg) -> received.set(msg));

        // Publisher client
        MqttClient publisher = createTlsClient("mtls-pub-1");
        MqttConnectOptions pubOpts = new MqttConnectOptions();
        pubOpts.setCleanSession(true);
        pubOpts.setConnectionTimeout(10);
        pubOpts.setSocketFactory(socketFactory);
        publisher.connect(pubOpts);

        publisher.publish("sensor/data", "hello-mtls".getBytes(), 0, false);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> received.get() != null);

        assertThat(new String(received.get().getPayload())).isEqualTo("hello-mtls");
    }

}
