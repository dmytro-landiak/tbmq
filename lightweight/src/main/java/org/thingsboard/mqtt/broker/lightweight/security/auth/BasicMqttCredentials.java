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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Basic (username/password) MQTT credential value stored in RocksDB.
 *
 * <p>This object is JSON-serialized and stored as the {@code credentialValue} field of a
 * {@link LightweightCredential} with type {@link CredentialType#BASIC}.
 *
 * <p>The password field holds the bcrypt hash of the raw password (never plain text).
 * The {@code authRules} field defines the topic-level ACL for this credential.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BasicMqttCredentials {

    /** BCrypt-hashed password. Use {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder} for hashing and matching. */
    private String password;

    /** Publish/subscribe ACL rules for this credential. */
    private PubSubAuthorizationRules authRules;

}
