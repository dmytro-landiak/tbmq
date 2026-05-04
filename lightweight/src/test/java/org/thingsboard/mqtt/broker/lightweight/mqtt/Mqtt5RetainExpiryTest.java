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
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for MQTT 5.0 [MQTT-3.3.2-6]: when a stored (retained) message is
 * forwarded to a late subscriber, the outbound PUBLISH MUST carry a Message Expiry
 * Interval reduced by the time the message has been waiting in the broker.
 *
 * <p>The fix wires the previously-dead {@code MqttPropertiesUtil.getRemainingExpiryInterval}
 * helper into the retained-on-subscribe delivery path so the outbound interval is
 * decremented by the in-broker dwell time rather than copied as-is from the inbound
 * PUBLISH.
 */
class Mqtt5RetainExpiryTest extends AbstractMqtt5IntegrationTest {

    private static final long ORIGINAL_EXPIRY_SECONDS = 60L;
    private static final long DWELL_MILLIS = 2_500L;

    @Test
    void retainedMessageExpiryDecrementsByDwellTime() throws Exception {
        // Publish a retained message with a 60s Message Expiry Interval.
        MqttClient publisher = createV5Client("v5-retain-expiry-pub");
        publisher.connect(defaultV5ConnectOptions());

        MqttMessage retainedMsg = new MqttMessage("retained-with-expiry".getBytes());
        retainedMsg.setQos(0);
        retainedMsg.setRetained(true);
        MqttProperties retainedProps = new MqttProperties();
        retainedProps.setMessageExpiryInterval(ORIGINAL_EXPIRY_SECONDS);
        retainedMsg.setProperties(retainedProps);
        publisher.publish("retain/expiry-decrement", retainedMsg);
        publisher.disconnect(1000);

        // Let the message dwell in the broker.  The decrement has 1-second granularity,
        // so we sleep > 2s to make the observable difference deterministic.
        Thread.sleep(DWELL_MILLIS);

        // A late subscriber should receive the retained message with a strictly smaller
        // Message Expiry Interval (per MQTT 5.0 [MQTT-3.3.2-6]).
        MqttClient subscriber = createV5Client("v5-retain-expiry-sub");
        subscriber.connect(defaultV5ConnectOptions());

        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {}
            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.set(message);
            }
            @Override
            public void deliveryComplete(IMqttToken token) {}
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}
            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        subscriber.subscribe("retain/expiry-decrement", 0);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);

        MqttMessage delivered = received.get();
        assertThat(delivered).isNotNull();
        assertThat(delivered.getProperties()).isNotNull();

        Long deliveredExpiry = delivered.getProperties().getMessageExpiryInterval();
        assertThat(deliveredExpiry)
                .as("Message Expiry Interval must be present on retained delivery")
                .isNotNull();
        assertThat(deliveredExpiry)
                .as("Message Expiry Interval must be decremented by broker dwell time")
                .isLessThan(ORIGINAL_EXPIRY_SECONDS);
        // Loose lower bound to defend against scheduler jitter while still catching a
        // clearly-wrong value (e.g. unchanged 60).  Anything between 56 and 59 inclusive
        // is acceptable for a ~2.5s dwell on a single-node test broker.
        assertThat(deliveredExpiry)
                .as("decremented interval should be within plausible jitter window")
                .isBetween(ORIGINAL_EXPIRY_SECONDS - 5, ORIGINAL_EXPIRY_SECONDS - 1);
    }
}
