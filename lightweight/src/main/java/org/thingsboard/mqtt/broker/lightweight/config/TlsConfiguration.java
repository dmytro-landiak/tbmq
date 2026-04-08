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
 * TLS/SSL listener configuration for the MQTT broker.
 *
 * <p>All properties map to the {@code tbmq.tls.*} namespace in {@code tbmq-lightweight.yml}
 * and can be overridden via environment variables:
 * <ul>
 *   <li>{@code TBMQ_TLS_ENABLED} — enable TLS listener (default: false)</li>
 *   <li>{@code TBMQ_MQTTS_PORT} — TLS listener port (default: 8883)</li>
 *   <li>{@code TBMQ_TLS_CERT_PATH} — path to server certificate PEM file</li>
 *   <li>{@code TBMQ_TLS_KEY_PATH} — path to server private key PEM file</li>
 *   <li>{@code TBMQ_TLS_CLIENT_AUTH} — client auth mode: NONE, OPTIONAL, REQUIRED (default: NONE)</li>
 *   <li>{@code TBMQ_TLS_TRUST_CERT_PATH} — path to CA certificate PEM for client cert verification</li>
 * </ul>
 */
@Configuration
@Data
public class TlsConfiguration {

    @Value("${tbmq.tls.enabled:false}")
    private boolean enabled;

    @Value("${tbmq.tls.port:8883}")
    private int port;

    @Value("${tbmq.tls.cert-path:}")
    private String certPath;

    @Value("${tbmq.tls.key-path:}")
    private String keyPath;

    @Value("${tbmq.tls.client-auth:NONE}")
    private String clientAuth;

    @Value("${tbmq.tls.trust-cert-path:}")
    private String trustCertPath;

}
