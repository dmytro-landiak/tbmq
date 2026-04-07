package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMqttIntegrationTest {

    @Autowired
    protected MqttTcpServerBootstrap mqttServer;

    protected final List<MqttClient> clients = new ArrayList<>();

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
     */
    protected MqttConnectOptions defaultConnectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setKeepAliveInterval(30);
        return opts;
    }

    /**
     * Disconnects and closes all clients created during the test to avoid resource leaks.
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
    }

}
