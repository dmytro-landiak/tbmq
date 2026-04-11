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

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for MQTT 5.0 integration tests.
 *
 * <p>Extends the MQTT 3.1.1 base class and adds v5-specific client creation
 * and connection options. Uses {@code org.eclipse.paho.mqttv5.client} API.
 *
 * <p>IMPORTANT: The Paho v5 API class names differ from v3:
 * <ul>
 *   <li>{@code MqttConnectionOptions} (not {@code MqttConnectOptions})</li>
 *   <li>{@code MqttMessage} is from {@code org.eclipse.paho.mqttv5.common.MqttMessage}</li>
 *   <li>{@code subscribe()} takes {@code MqttSubscription} objects</li>
 *   <li>{@code setPassword(byte[])} takes a byte array (not char[])</li>
 *   <li>{@code setCleanStart(true)} is the v5 equivalent of {@code setCleanSession(true)}</li>
 * </ul>
 */
public abstract class AbstractMqtt5IntegrationTest extends AbstractMqttIntegrationTest {

    protected final List<MqttClient> v5Clients = new ArrayList<>();

    /**
     * Creates a Paho MQTT v5 client connected to the broker URL.
     * The client is registered for automatic cleanup after the test.
     *
     * @param clientId the MQTT client identifier
     * @return an unconnected {@link MqttClient} instance
     */
    protected MqttClient createV5Client(String clientId) throws Exception {
        MqttClient client = new MqttClient(brokerUrl(), clientId, new MemoryPersistence());
        v5Clients.add(client);
        return client;
    }

    /**
     * Default MQTT 5.0 connection options with {@code tbmq/tbmq} credentials.
     * Uses clean start (equivalent of v3 clean session), 5-second timeout, 30-second keep-alive.
     */
    protected MqttConnectionOptions defaultV5ConnectOptions() {
        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setCleanStart(true);
        opts.setConnectionTimeout(5);
        opts.setKeepAliveInterval(30);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".getBytes());
        return opts;
    }

    /**
     * MQTT 5.0 connection options with custom credentials.
     */
    protected MqttConnectionOptions authV5ConnectOptions(String username, String password) {
        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setCleanStart(true);
        opts.setConnectionTimeout(5);
        opts.setKeepAliveInterval(30);
        opts.setUserName(username);
        opts.setPassword(password.getBytes());
        return opts;
    }

    /**
     * Disconnects and closes all v5 clients created during the test.
     * Called after each test method, complementing the v3 cleanup in the parent class.
     */
    @AfterEach
    void disconnectV5Clients() {
        for (MqttClient client : v5Clients) {
            try {
                if (client.isConnected()) {
                    client.disconnect(1000);
                }
                client.close();
            } catch (Exception ignored) {
            }
        }
        v5Clients.clear();
    }

}
