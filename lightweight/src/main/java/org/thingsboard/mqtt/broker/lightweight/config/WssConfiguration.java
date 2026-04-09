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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Secure WebSocket (WSS) listener configuration for the MQTT broker.
 *
 * <p>All properties map to the {@code tbmq.wss.*} namespace in {@code tbmq-lightweight.yml}
 * and can be overridden via environment variables:
 * <ul>
 *   <li>{@code TBMQ_WSS_ENABLED} — enable Secure WebSocket listener (default: false)</li>
 *   <li>{@code TBMQ_WSS_PORT} — Secure WebSocket listener port (default: 8085)</li>
 *   <li>{@code TBMQ_WSS_SUB_PROTOCOLS} — WebSocket sub-protocols (default: mqttv3.1,mqtt)</li>
 *   <li>{@code TBMQ_WSS_MAX_CONTENT_LENGTH} — max HTTP content length for handshake (default: 65536)</li>
 * </ul>
 */
@Configuration
@Data
public class WssConfiguration {

    @Value("${tbmq.wss.enabled:false}")
    private boolean enabled;

    @Value("${tbmq.wss.port:8085}")
    private int port;

    @Value("${tbmq.wss.sub-protocols:mqttv3.1,mqtt}")
    private String subProtocols;

    @Value("${tbmq.wss.max-content-length:65536}")
    private int maxContentLength;

}
