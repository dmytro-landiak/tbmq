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
 * Top-level MQTT credential record stored in RocksDB {@code CREDENTIALS} column family.
 *
 * <p>Key in RocksDB: {@link #credentialId} (username for BASIC auth, CN for SSL auth).
 * Value in RocksDB: JSON-serialized form of this object.
 *
 * <p>The {@link #credentialValue} field contains a nested JSON string representing either
 * a {@link BasicMqttCredentials} (type=BASIC) or {@link SslMqttCredentials} (type=SSL).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LightweightCredential {

    /** Credential identifier. For BASIC: username. For SSL: certificate CN or fingerprint. */
    private String credentialId;

    /** The type of credential (BASIC or SSL). */
    private CredentialType type;

    /**
     * JSON-serialized credential value. Deserialize with Jackson to either
     * {@link BasicMqttCredentials} (if type=BASIC) or {@link SslMqttCredentials} (if type=SSL).
     */
    private String credentialValue;

}
