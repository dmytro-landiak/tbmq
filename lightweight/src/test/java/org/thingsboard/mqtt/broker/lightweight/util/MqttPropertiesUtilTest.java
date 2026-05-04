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
package org.thingsboard.mqtt.broker.lightweight.util;

import io.netty.handler.codec.mqtt.MqttProperties;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.common.BrokerConstants;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MqttPropertiesUtil#copyPublishPropertiesToDeliver(MqttProperties, long)}
 * — the two-arg overload that recomputes the Message Expiry Interval per
 * MQTT 5.0 [MQTT-3.3.2-6].
 *
 * <p>Pins edge cases that the integration test {@code Mqtt5RetainedMsgExpiryTest}
 * cannot reach: the defensive non-{@link MqttProperties.IntegerProperty} fallback,
 * the zero-remaining omission branch, and the null/{@link MqttProperties#NO_PROPERTIES}
 * source short-circuits.
 */
class MqttPropertiesUtilTest {

    @Test
    void nonIntegerMessageExpiryPropertyIsDropped() {
        // Construct a malformed source: a StringProperty masquerading as MESSAGE_EXPIRY_INTERVAL.
        // The defensive `else if (instanceof IntegerProperty)` branch in copyPublishProperties
        // should silently drop it rather than copying it into the destination.
        MqttProperties source = new MqttProperties();
        source.add(new MqttProperties.StringProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID, "not-an-integer"));

        MqttProperties dest = MqttPropertiesUtil.copyPublishPropertiesToDeliver(source, System.currentTimeMillis());

        assertThat(dest.getProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID))
                .as("Non-IntegerProperty masquerading as MESSAGE_EXPIRY_INTERVAL must be dropped")
                .isNull();
    }

    @Test
    void zeroRemainingExpiryOmitsProperty() {
        // Original interval = 1s; createdTime = 5s ago → remaining = 0 → property must be omitted.
        MqttProperties source = new MqttProperties();
        source.add(new MqttProperties.IntegerProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID, 1));

        long createdTimeMillis = System.currentTimeMillis() - 5_000L;
        MqttProperties dest = MqttPropertiesUtil.copyPublishPropertiesToDeliver(source, createdTimeMillis);

        assertThat(dest.getProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID))
                .as("When remaining expiry interval has elapsed, MESSAGE_EXPIRY_INTERVAL must be omitted")
                .isNull();
    }

    @Test
    void nullSourceReturnsEmpty() {
        // Null source must short-circuit to an empty MqttProperties (never null, never throws).
        MqttProperties dest = MqttPropertiesUtil.copyPublishPropertiesToDeliver(null, System.currentTimeMillis());

        assertThat(dest)
                .as("Null source must produce a non-null empty MqttProperties")
                .isNotNull();
        assertThat(dest.isEmpty())
                .as("Null source must produce an empty MqttProperties")
                .isTrue();
    }

    @Test
    void noPropertiesSourceReturnsEmpty() {
        // The MqttProperties.NO_PROPERTIES sentinel must short-circuit to an empty result.
        MqttProperties dest = MqttPropertiesUtil.copyPublishPropertiesToDeliver(MqttProperties.NO_PROPERTIES, System.currentTimeMillis());

        assertThat(dest)
                .as("NO_PROPERTIES source must produce a non-null empty MqttProperties")
                .isNotNull();
        assertThat(dest.isEmpty())
                .as("NO_PROPERTIES source must produce an empty MqttProperties")
                .isTrue();
    }

}
