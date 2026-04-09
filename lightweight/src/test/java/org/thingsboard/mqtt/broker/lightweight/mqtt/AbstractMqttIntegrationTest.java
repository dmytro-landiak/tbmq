package org.thingsboard.mqtt.broker.lightweight.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.security.auth.BasicMqttCredentials;
import org.thingsboard.mqtt.broker.lightweight.security.auth.CredentialType;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredential;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredentialService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.PubSubAuthorizationRules;
import org.thingsboard.mqtt.broker.lightweight.server.MqttTcpServerBootstrap;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for MQTT integration tests.
 *
 * <p>Starts a full Spring Boot context with a random Netty port and a temporary
 * RocksDB storage path so tests are isolated from each other and from production data.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #brokerUrl()} — the {@code tcp://127.0.0.1:<port>} URL for the broker under test</li>
 *   <li>{@link #createClient(String)} — factory method that registers clients for auto-cleanup</li>
 *   <li>{@link #defaultConnectOptions()} — sensible defaults for test connections</li>
 * </ul>
 *
 * <p>All clients created via {@link #createClient(String)} are automatically disconnected and
 * closed after each test via the {@link #disconnectClients()} {@link AfterEach} callback.
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-mqtt",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMqttIntegrationTest {

    @Autowired
    protected MqttTcpServerBootstrap mqttServer;

    @Autowired
    protected LightweightCredentialService credentialService;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected BCryptPasswordEncoder passwordEncoder;

    protected final List<MqttClient> clients = new ArrayList<>();

    /** Credentials created during a test that need to be cleaned up. */
    protected final List<String> createdCredentialIds = new ArrayList<>();

    /**
     * Returns the broker URL for test clients to connect to.
     */
    protected String brokerUrl() {
        return "tcp://127.0.0.1:" + mqttServer.getLocalPort();
    }

    /**
     * Creates a Paho {@link MqttClient} connected to the broker URL.
     * The client is registered for automatic cleanup after the test.
     *
     * @param clientId the MQTT client identifier
     * @return an unconnected {@link MqttClient} instance
     */
    protected MqttClient createClient(String clientId) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl(), clientId, new MemoryPersistence());
        clients.add(client);
        return client;
    }

    /**
     * Returns sensible default {@link MqttConnectOptions} suitable for integration tests:
     * clean session enabled, 5-second connection timeout, 30-second keep-alive.
     * Uses the built-in {@code tbmq/tbmq} credentials so that auth enforcement is satisfied.
     */
    protected MqttConnectOptions defaultConnectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setKeepAliveInterval(30);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".toCharArray());
        return opts;
    }

    /**
     * Returns {@link MqttConnectOptions} with the given username and password.
     */
    protected MqttConnectOptions authConnectOptions(String username, String password) {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setKeepAliveInterval(30);
        opts.setUserName(username);
        opts.setPassword(password.toCharArray());
        return opts;
    }

    /**
     * Creates and saves a BASIC credential with the given username, password, and ACL patterns.
     * The credential is registered for automatic cleanup in {@code @AfterEach}.
     *
     * @param username    the MQTT username
     * @param password    the plain-text password (will be bcrypt-hashed before storing)
     * @param pubPatterns regex patterns for allowed publish topics
     * @param subPatterns regex patterns for allowed subscribe topic filters
     */
    protected void createBasicCredential(String username, String password,
                                         List<String> pubPatterns, List<String> subPatterns) {
        try {
            String hashedPassword = passwordEncoder.encode(password);
            BasicMqttCredentials basicCreds = new BasicMqttCredentials(
                    hashedPassword, new PubSubAuthorizationRules(pubPatterns, subPatterns));
            String credentialValue = objectMapper.writeValueAsString(basicCreds);
            LightweightCredential credential = new LightweightCredential(username, CredentialType.BASIC, credentialValue);
            credentialService.saveCredential(credential);
            createdCredentialIds.add(username);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test credential for username: " + username, e);
        }
    }

    /**
     * Disconnects and closes all clients created during the test to avoid resource leaks.
     * Also deletes any test credentials created via {@link #createBasicCredential}.
     */
    @AfterEach
    void disconnectClients() {
        for (MqttClient client : clients) {
            try {
                if (client.isConnected()) {
                    client.disconnect(1000);
                }
                client.close();
            } catch (MqttException ignored) {
            }
        }
        clients.clear();
        // Clean up test credentials
        for (String credentialId : createdCredentialIds) {
            try {
                credentialService.deleteCredential(credentialId);
            } catch (Exception ignored) {
            }
        }
        createdCredentialIds.clear();
    }

}
