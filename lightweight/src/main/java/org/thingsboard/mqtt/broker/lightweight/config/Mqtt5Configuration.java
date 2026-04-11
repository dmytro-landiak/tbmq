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
package org.thingsboard.mqtt.broker.lightweight.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT 5.0 protocol configuration.
 *
 * <p>All properties map to the {@code tbmq.mqtt5.*} namespace in {@code tbmq-lightweight.yml}
 * and can be overridden via environment variables:
 * <ul>
 *   <li>{@code TBMQ_TOPIC_ALIAS_MAX} — maximum topic aliases per session (default: 10).
 *       Per D-13: default 10 balances memory vs. alias benefit. Set to 0 to disable.</li>
 *   <li>{@code TBMQ_RECEIVE_MAXIMUM} — maximum in-flight QoS 1/2 messages per client (default: 65535).</li>
 *   <li>{@code TBMQ_MIN_TOPIC_ALIAS_LENGTH} — minimum topic name length that qualifies for aliasing (default: 5).
 *       Short topic names do not benefit from aliasing due to alias encoding overhead.</li>
 * </ul>
 */
@Getter
@Configuration
public class Mqtt5Configuration {

    @Value("${tbmq.mqtt5.topic-alias-max:10}")
    private int topicAliasMax;

    @Value("${tbmq.mqtt5.receive-maximum:65535}")
    private int receiveMaximum;

    @Value("${tbmq.mqtt5.min-topic-alias-length:5}")
    private int minTopicAliasLength;

}
