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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT protocol configuration properties.
 *
 * <p>All properties can be overridden via environment variables:
 * <ul>
 *   <li>TBMQ_MQTT_MAX_PAYLOAD_SIZE (default: 268435456 = 256 MB)</li>
 *   <li>TBMQ_MQTT_MAX_CLIENT_ID_LENGTH (default: 128 characters)</li>
 *   <li>TBMQ_MQTT_KEEP_ALIVE_MAX (default: 600 seconds)</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "tbmq.mqtt")
@Data
public class MqttConfiguration {

    /** Maximum allowed PUBLISH payload size in bytes. Default: 256 MB. */
    private int maxPayloadSize = 268435456;

    /**
     * Maximum allowed clientId length in characters.
     * MQTT 3.1.1 SHOULD limit is 23, but many clients use longer IDs.
     */
    private int maxClientIdLength = 128;

    /** Maximum allowed keep-alive interval in seconds. Default: 600 (10 minutes). */
    private int keepAliveMax = 600;

}
