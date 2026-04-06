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
package org.thingsboard.mqtt.broker.lightweight.service.subscription;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;

/**
 * Represents a single MQTT subscription for a client.
 *
 * <p>Stored in the {@link SubscriptionRegistry} keyed by topic filter.
 * The {@code sessionCtx} reference is used for inline message delivery (D-04).
 */
@Data
@AllArgsConstructor
public class Subscription {

    /** The MQTT client identifier that owns this subscription. */
    private final String clientId;

    /** Granted QoS level (0, 1, or 2) — may be downgraded from requested QoS. */
    private final int qos;

    /** Session context reference used for direct inline delivery to the subscriber's channel. */
    private final ClientSessionCtx sessionCtx;

}
