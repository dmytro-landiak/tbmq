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
package org.thingsboard.mqtt.broker.lightweight.security.acl;

import org.thingsboard.mqtt.broker.lightweight.security.auth.AuthRulePatterns;
import org.thingsboard.mqtt.broker.lightweight.security.auth.BasicMqttCredentials;
import org.thingsboard.mqtt.broker.lightweight.security.auth.SslMqttCredentials;

import java.util.List;

/**
 * Service for topic-level ACL authorization checks.
 *
 * <p>Deny-by-default: if patterns are present and none match, access is denied.
 * Empty patterns list (anonymous session) bypasses all ACL checks (returns {@code true}).
 */
public interface AuthorizationRuleService {

    /**
     * Checks if a client is authorized to publish to a topic.
     *
     * @param clientId the MQTT client ID (used for per-client caching)
     * @param topic    the topic being published to
     * @param patterns the compiled ACL patterns for the session (empty = anonymous, allow all)
     * @return {@code true} if publishing is allowed, {@code false} otherwise
     */
    boolean isPubAuthorized(String clientId, String topic, List<AuthRulePatterns> patterns);

    /**
     * Checks if a client is authorized to subscribe to a topic filter.
     *
     * @param topicFilter the topic filter being subscribed to
     * @param patterns    the compiled ACL patterns for the session (empty = anonymous, allow all)
     * @return {@code true} if subscribing is allowed, {@code false} otherwise
     */
    boolean isSubAuthorized(String topicFilter, List<AuthRulePatterns> patterns);

    /**
     * Compiles ACL patterns from basic credential rules.
     *
     * @param creds the basic credential containing pub/sub pattern strings
     * @return single-element list of compiled {@link AuthRulePatterns}
     */
    List<AuthRulePatterns> parseAuthorizationRule(BasicMqttCredentials creds);

    /**
     * Compiles ACL patterns from SSL credential rules matching the given CN.
     *
     * @param creds the SSL credential containing CN-to-rules mapping
     * @param cn    the client certificate common name
     * @return list of {@link AuthRulePatterns} for all matching CN entries
     */
    List<AuthRulePatterns> parseSslAuthorizationRule(SslMqttCredentials creds, String cn);

    /**
     * Evicts the publish authorization cache for the given client ID.
     * Called on client disconnect to prevent stale cache entries.
     *
     * @param clientId the MQTT client ID to evict
     */
    void evict(String clientId);

}
