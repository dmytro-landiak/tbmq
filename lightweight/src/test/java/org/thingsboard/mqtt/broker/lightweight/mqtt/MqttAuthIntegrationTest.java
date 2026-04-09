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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for MQTT authentication — AUTH-01 and AUTH-04.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Valid credentials are accepted (CONNACK ACCEPTED)</li>
 *   <li>Invalid credentials are rejected (CONNACK NOT_AUTHORIZED)</li>
 *   <li>Anonymous connections are rejected by default</li>
 *   <li>Custom credentials work after creation</li>
 *   <li>Nonexistent users are rejected</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-auth",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttAuthIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void givenDefaultCredentials_whenConnectWithValidPassword_thenConnackAccepted() throws Exception {
        MqttClient client = createClient("auth-valid-1");
        client.connect(authConnectOptions("tbmq", "tbmq"));
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    @Test
    void givenDefaultCredentials_whenConnectWithInvalidPassword_thenConnackRefused() throws Exception {
        MqttClient client = createClient("auth-invalid-1");
        assertThatThrownBy(() -> client.connect(authConnectOptions("tbmq", "wrong")))
                .isInstanceOf(MqttException.class)
                .satisfies(e -> assertThat(((MqttException) e).getReasonCode()).isEqualTo(5)); // NOT_AUTHORIZED
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void givenNoCredentials_whenConnectAnonymously_thenDefaultDenied() throws Exception {
        // Anonymous is disabled by default (tbmq.security.anonymous-enabled=false)
        MqttClient client = createClient("auth-anon-1");
        org.eclipse.paho.client.mqttv3.MqttConnectOptions opts = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        // No username/password — anonymous connection
        assertThatThrownBy(() -> client.connect(opts))
                .isInstanceOf(MqttException.class)
                .satisfies(e -> assertThat(((MqttException) e).getReasonCode()).isEqualTo(5)); // NOT_AUTHORIZED
    }

    @Test
    void givenCustomCredentials_whenConnectWithValidPassword_thenConnackAccepted() throws Exception {
        createBasicCredential("testuser", "testpass", List.of(".*"), List.of(".*"));

        MqttClient client = createClient("auth-custom-1");
        client.connect(authConnectOptions("testuser", "testpass"));
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    @Test
    void givenNonexistentUser_whenConnect_thenConnackRefused() throws Exception {
        MqttClient client = createClient("auth-noexist-1");
        assertThatThrownBy(() -> client.connect(authConnectOptions("nobody", "whatever")))
                .isInstanceOf(MqttException.class)
                .satisfies(e -> assertThat(((MqttException) e).getReasonCode()).isEqualTo(5)); // NOT_AUTHORIZED
        assertThat(client.isConnected()).isFalse();
    }

}
