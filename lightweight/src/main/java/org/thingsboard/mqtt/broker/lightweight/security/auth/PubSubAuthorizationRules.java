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

import java.util.List;

/**
 * Topic-level ACL rules for a single credential, stored as regex pattern strings.
 *
 * <p>Patterns use Java regex syntax:
 * <ul>
 *   <li>{@code ".*"} — matches any topic (allow-all)</li>
 *   <li>{@code "sensor/.*"} — matches any topic under the sensor/ prefix</li>
 * </ul>
 *
 * <p>These string patterns are compiled into {@link AuthRulePatterns} (with pre-compiled
 * {@link java.util.regex.Pattern} objects) during authentication for efficient reuse.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PubSubAuthorizationRules {

    /** Regex patterns for topics the client is allowed to publish to. */
    private List<String> pubPatterns;

    /** Regex patterns for topic filters the client is allowed to subscribe to. */
    private List<String> subPatterns;

    /**
     * Returns an allow-all rule set (publish and subscribe to any topic).
     * Used as the default rule for the built-in {@code tbmq} credential.
     */
    public static PubSubAuthorizationRules defaultInstance() {
        return new PubSubAuthorizationRules(List.of(".*"), List.of(".*"));
    }

}
