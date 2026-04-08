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

import lombok.Data;

import java.util.List;

/**
 * Result of an MQTT client authentication attempt.
 *
 * <p>On success: {@link #success} is {@code true} and {@link #authRulePatterns} contains
 * the pre-compiled ACL patterns for the session. An empty patterns list means the client
 * is anonymous and bypasses all ACL checks.
 *
 * <p>On failure: {@link #success} is {@code false} and {@link #failureReason} contains
 * a human-readable message for logging. The client must be disconnected with CONNACK NOT_AUTHORIZED.
 */
@Data
public class AuthResult {

    private final boolean success;
    private final String failureReason;
    private final List<AuthRulePatterns> authRulePatterns;

    private AuthResult(boolean success, String failureReason, List<AuthRulePatterns> authRulePatterns) {
        this.success = success;
        this.failureReason = failureReason;
        this.authRulePatterns = authRulePatterns;
    }

    /**
     * Creates a successful authentication result with the given ACL patterns.
     *
     * @param authRulePatterns compiled ACL patterns for the session; empty list for anonymous clients
     */
    public static AuthResult success(List<AuthRulePatterns> authRulePatterns) {
        return new AuthResult(true, null, authRulePatterns);
    }

    /**
     * Creates a failed authentication result with a reason message.
     *
     * @param reason human-readable failure reason for logging
     */
    public static AuthResult failure(String reason) {
        return new AuthResult(false, reason, null);
    }

}
