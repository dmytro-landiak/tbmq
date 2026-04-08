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
package org.thingsboard.mqtt.broker.lightweight.security.auth;

/**
 * Enumeration of MQTT client credential types supported by TBMQ Lightweight.
 *
 * <ul>
 *   <li>{@link #BASIC} — Username/password authentication. Credential value is a JSON-serialized
 *       {@link BasicMqttCredentials} containing the bcrypt-hashed password and ACL rules.</li>
 *   <li>{@link #SSL} — X.509 certificate authentication. Credential value is a JSON-serialized
 *       {@link SslMqttCredentials} containing CN-to-rules mapping.</li>
 * </ul>
 */
public enum CredentialType {

    BASIC,
    SSL

}
