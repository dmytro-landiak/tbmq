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
package org.thingsboard.mqtt.broker.lightweight.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.mqtt.broker.lightweight.mqtt.AbstractMqttIntegrationTest;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that all Prometheus metrics are correctly
 * registered and incremented after MQTT protocol operations.
 *
 * <p>Tests share a single Spring context ({@code @DirtiesContext(AFTER_CLASS)}).
 * Counter assertions use {@code >= 1.0} to handle accumulated state across tests.
 */
public class BrokerMetricsIT extends AbstractMqttIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void givenPublishedMessage_whenScrapingMetrics_thenReceivedCounterIncremented() throws Exception {
        double before = meterRegistry.get("mqtt.messages.received.total").counter().count();

        MqttClient publisher = createClient("metrics-pub-test");
        publisher.connect(defaultConnectOptions());
        publisher.publish("metrics/test", "hello".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(meterRegistry.get("mqtt.messages.received.total").counter().count())
                                .isGreaterThanOrEqualTo(before + 1.0));
    }

    @Test
    void givenDeliveredMessage_whenScrapingMetrics_thenDeliveredCounterIncremented() throws Exception {
        double before = meterRegistry.get("mqtt.messages.delivered.total").counter().count();

        MqttClient subscriber = createClient("metrics-sub-test");
        subscriber.connect(defaultConnectOptions());
        AtomicBoolean received = new AtomicBoolean(false);
        subscriber.subscribe("metrics/deliver", (topic, message) -> received.set(true));

        MqttClient publisher = createClient("metrics-pub-deliver");
        publisher.connect(defaultConnectOptions());
        publisher.publish("metrics/deliver", "payload".getBytes(), 0, false);

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(received::get);

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(meterRegistry.get("mqtt.messages.delivered.total").counter().count())
                                .isGreaterThanOrEqualTo(before + 1.0));
    }

    @Test
    void givenSuccessfulAuth_whenScrapingMetrics_thenAuthSuccessCounterIncremented() throws Exception {
        double before = meterRegistry.get("mqtt.auth.success.total").counter().count();

        MqttClient client = createClient("metrics-auth-success");
        client.connect(defaultConnectOptions());

        assertThat(meterRegistry.get("mqtt.auth.success.total").counter().count())
                .isGreaterThanOrEqualTo(before + 1.0);
    }

    @Test
    void givenFailedAuth_whenScrapingMetrics_thenAuthFailureCounterIncremented() throws Exception {
        double before = meterRegistry.get("mqtt.auth.failure.total").counter().count();

        MqttClient badClient = createClient("metrics-auth-fail");
        MqttConnectOptions badOpts = new MqttConnectOptions();
        badOpts.setCleanSession(true);
        badOpts.setConnectionTimeout(5);
        badOpts.setUserName("baduser");
        badOpts.setPassword("badpass".toCharArray());

        try {
            badClient.connect(badOpts);
        } catch (MqttException e) {
            // Expected — authentication rejected
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(meterRegistry.get("mqtt.auth.failure.total").counter().count())
                                .isGreaterThanOrEqualTo(before + 1.0));
    }

    @Test
    void givenIdleBroker_whenScrapingMetrics_thenQueueDepthIsZero() {
        // When no messages are in transit, dispatch queue depth should be 0
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(meterRegistry.get("mqtt.dispatch.queue.depth").gauge().value())
                                .isEqualTo(0.0));
    }

}
