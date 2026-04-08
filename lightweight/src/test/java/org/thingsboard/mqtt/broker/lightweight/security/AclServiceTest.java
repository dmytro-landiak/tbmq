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
package org.thingsboard.mqtt.broker.lightweight.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.security.acl.DefaultAuthorizationRuleService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.AuthRulePatterns;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultAuthorizationRuleService}.
 * Tests regex-based topic ACL enforcement for publish and subscribe.
 */
class AclServiceTest {

    private DefaultAuthorizationRuleService authorizationRuleService;

    @BeforeEach
    void setUp() {
        authorizationRuleService = new DefaultAuthorizationRuleService();
    }

    @Test
    void isPubAuthorized_matchingPattern_returnsTrue() {
        // Given
        AuthRulePatterns patterns = new AuthRulePatterns(
                List.of(Pattern.compile("sensor/.*")),
                Collections.emptyList());

        // When
        boolean result = authorizationRuleService.isPubAuthorized("client1", "sensor/temp", List.of(patterns));

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void isPubAuthorized_nonMatchingPattern_returnsFalse() {
        // Given
        AuthRulePatterns patterns = new AuthRulePatterns(
                List.of(Pattern.compile("sensor/.*")),
                Collections.emptyList());

        // When
        boolean result = authorizationRuleService.isPubAuthorized("client1", "admin/config", List.of(patterns));

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void isSubAuthorized_matchingPattern_returnsTrue() {
        // Given
        AuthRulePatterns patterns = new AuthRulePatterns(
                Collections.emptyList(),
                List.of(Pattern.compile("sensor/.*")));

        // When
        boolean result = authorizationRuleService.isSubAuthorized("sensor/+", List.of(patterns));

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void isSubAuthorized_nonMatchingPattern_returnsFalse() {
        // Given
        AuthRulePatterns patterns = new AuthRulePatterns(
                Collections.emptyList(),
                List.of(Pattern.compile("sensor/.*")));

        // When
        boolean result = authorizationRuleService.isSubAuthorized("admin/#", List.of(patterns));

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void isPubAuthorized_emptyPatternsList_returnsTrue() {
        // When - empty patterns = anonymous / no-ACL path
        boolean result = authorizationRuleService.isPubAuthorized("client1", "any/topic", Collections.emptyList());

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void isSubAuthorized_emptyPatternsList_returnsTrue() {
        // When - empty patterns = anonymous / no-ACL path
        boolean result = authorizationRuleService.isSubAuthorized("any/#", Collections.emptyList());

        // Then
        assertThat(result).isTrue();
    }

}
