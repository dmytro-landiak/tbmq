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

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-compiled regex patterns for topic-level ACL enforcement.
 *
 * <p>Holds compiled {@link Pattern} objects for fast topic matching during PUBLISH and SUBSCRIBE
 * processing. Created from {@link PubSubAuthorizationRules} during authentication and stored
 * in the session context for reuse across messages.
 *
 * <p>Per session a list of {@link AuthRulePatterns} may exist (e.g., for SSL credentials with
 * multiple CN-matching rule sets). Authorization succeeds if any rule set permits the operation.
 */
@Data
@AllArgsConstructor
public class AuthRulePatterns {

    /** Pre-compiled regex patterns for allowed publish topics. */
    private List<Pattern> pubPatterns;

    /** Pre-compiled regex patterns for allowed subscribe topic filters. */
    private List<Pattern> subPatterns;

}
