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
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for topic-level ACL enforcement — AUTH-03.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Publish to allowed topic is delivered to subscribers</li>
 *   <li>Publish to denied topic is silently dropped</li>
 *   <li>Subscribe to denied topic receives SUBACK with 0x80 failure code</li>
 *   <li>Default tbmq/tbmq credentials allow all topics (pub and sub)</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=100",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-acl",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttAclIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void givenRestrictedPubAcl_whenPublishToAllowedTopic_thenMessageDelivered() throws Exception {
        createBasicCredential("acluser", "aclpass", List.of("sensor/.*"), List.of("sensor/.*"));

        MqttClient subscriber = createClient("acl-sub-1");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("sensor/temp", 0, (topic, msg) -> received.set(msg.getPayload()));

        MqttClient publisher = createClient("acl-pub-1");
        publisher.connect(authConnectOptions("acluser", "aclpass"));
        publisher.publish("sensor/temp", "25".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("25");
    }

    @Test
    void givenRestrictedPubAcl_whenPublishToDeniedTopic_thenMessageDropped() throws Exception {
        createBasicCredential("acluser2", "aclpass2", List.of("sensor/.*"), List.of(".*"));

        MqttClient subscriber = createClient("acl-sub-2");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("admin/config", 0, (topic, msg) -> received.set(msg.getPayload()));

        MqttClient publisher = createClient("acl-pub-2");
        publisher.connect(authConnectOptions("acluser2", "aclpass2"));
        // admin/config is NOT in acluser2's pubPatterns (only "sensor/.*" is allowed)
        publisher.publish("admin/config", "secret".getBytes(), 0, false);

        // Wait briefly and assert subscriber did NOT receive any message
        Thread.sleep(1000);
        assertThat(received.get()).isNull();
    }

    @Test
    void givenRestrictedSubAcl_whenSubscribeToDeniedTopic_thenSubackFailure() throws Exception {
        createBasicCredential("subuser", "subpass", List.of(".*"), List.of("sensor/.*"));

        MqttClient client = createClient("acl-sub-3");
        client.connect(authConnectOptions("subuser", "subpass"));

        // Subscribing to "admin/#" is denied by subPatterns (only "sensor/.*" is allowed).
        // Paho will throw MqttException when SUBACK contains failure code 0x80.
        assertThatThrownBy(() -> client.subscribe("admin/#", 0))
                .isInstanceOf(MqttException.class);
    }

    @Test
    void givenDefaultCredentials_whenPubSubAllTopics_thenAllowed() throws Exception {
        // Default tbmq/tbmq credential has allow-all ACL patterns (".*")
        MqttClient subscriber = createClient("acl-sub-4");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<byte[]> received = new AtomicReference<>();
        subscriber.subscribe("any/topic", 0, (topic, msg) -> received.set(msg.getPayload()));

        MqttClient publisher = createClient("acl-pub-4");
        publisher.connect(defaultConnectOptions());
        publisher.publish("any/topic", "hello".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get())).isEqualTo("hello");
    }

}
