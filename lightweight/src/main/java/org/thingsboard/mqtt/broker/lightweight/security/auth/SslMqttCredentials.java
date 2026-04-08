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

import java.util.Map;

/**
 * SSL/X.509 certificate MQTT credential value stored in RocksDB.
 *
 * <p>This object is JSON-serialized and stored as the {@code credentialValue} field of a
 * {@link LightweightCredential} with type {@link CredentialType#SSL}.
 *
 * <p>The {@code authRulesMapping} maps a CN regex pattern (key) to a set of topic ACL rules
 * (value). During authentication, the client's certificate CN is matched against each key regex,
 * and all matching rule sets are merged into the session's {@link AuthRulePatterns} list.
 *
 * <p>Modelled after TBMQ's {@code SslMqttCredentials} but without JPA/serialization annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SslMqttCredentials {

    /**
     * Maps CN regex pattern strings to their corresponding publish/subscribe ACL rules.
     * Key: Java regex pattern matching the client certificate CN.
     * Value: ACL rules to apply when the CN matches.
     */
    private Map<String, PubSubAuthorizationRules> authRulesMapping;

}
