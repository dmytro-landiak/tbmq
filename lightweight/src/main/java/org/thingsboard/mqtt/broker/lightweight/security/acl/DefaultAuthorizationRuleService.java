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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.security.auth.AuthRulePatterns;
import org.thingsboard.mqtt.broker.lightweight.security.auth.BasicMqttCredentials;
import org.thingsboard.mqtt.broker.lightweight.security.auth.SslMqttCredentials;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Regex-based topic ACL enforcement service.
 *
 * <p>Authorization logic (copied and adapted from TBMQ's {@code DefaultAuthorizationRuleService}):
 * <ul>
 *   <li>Empty patterns list → return {@code true} (anonymous session, bypasses ACL per D-12)</li>
 *   <li>Non-empty patterns with no matches → return {@code false} (deny-by-default per D-10)</li>
 *   <li>Non-empty patterns with at least one match → return {@code true}</li>
 * </ul>
 *
 * <p>Publish authorization results are cached per-client per-topic in {@link #publishAuthMap}
 * to avoid redundant regex matching on hot publish paths. The cache is evicted on disconnect
 * via {@link #evict(String)}.
 */
@Slf4j
@Service
public class DefaultAuthorizationRuleService implements AuthorizationRuleService {

    /**
     * Per-client per-topic publish authorization cache.
     * Outer key: clientId. Inner key: topic. Value: authorization result.
     */
    private final ConcurrentMap<String, ConcurrentMap<String, Boolean>> publishAuthMap = new ConcurrentHashMap<>();

    @Override
    public boolean isPubAuthorized(String clientId, String topic, List<AuthRulePatterns> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        ConcurrentMap<String, Boolean> topicAuthMap = publishAuthMap.computeIfAbsent(clientId, s -> new ConcurrentHashMap<>());
        return topicAuthMap.computeIfAbsent(topic, t -> isAuthorized(topic, patterns.stream()
                .map(AuthRulePatterns::getPubPatterns)
                .flatMap(List::stream)
                .collect(Collectors.toList())));
    }

    @Override
    public boolean isSubAuthorized(String topicFilter, List<AuthRulePatterns> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        List<Pattern> subPatterns = patterns.stream()
                .map(AuthRulePatterns::getSubPatterns)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        return isAuthorized(topicFilter, subPatterns);
    }

    @Override
    public List<AuthRulePatterns> parseAuthorizationRule(BasicMqttCredentials creds) {
        if (creds == null || creds.getAuthRules() == null) {
            return Collections.emptyList();
        }
        List<Pattern> pubPatterns = compilePatterns(creds.getAuthRules().getPubPatterns());
        List<Pattern> subPatterns = compilePatterns(creds.getAuthRules().getSubPatterns());
        return List.of(new AuthRulePatterns(pubPatterns, subPatterns));
    }

    @Override
    public List<AuthRulePatterns> parseSslAuthorizationRule(SslMqttCredentials creds, String cn) {
        if (creds == null || creds.getAuthRulesMapping() == null) {
            return Collections.emptyList();
        }
        return creds.getAuthRulesMapping().entrySet().stream()
                .filter(entry -> {
                    Pattern cnPattern = Pattern.compile(entry.getKey());
                    Matcher matcher = cnPattern.matcher(cn);
                    return matcher.find();
                })
                .map(Map.Entry::getValue)
                .map(rules -> new AuthRulePatterns(
                        compilePatterns(rules.getPubPatterns()),
                        compilePatterns(rules.getSubPatterns())))
                .collect(Collectors.toList());
    }

    @Override
    public void evict(String clientId) {
        if (clientId != null) {
            ConcurrentMap<String, Boolean> removed = publishAuthMap.remove(clientId);
            if (removed != null) {
                removed.clear();
            }
        }
    }

    private boolean isAuthorized(String topic, List<Pattern> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> pattern.matcher(topic).matches());
    }

    private List<Pattern> compilePatterns(List<String> patternStrings) {
        if (patternStrings == null || patternStrings.isEmpty()) {
            return Collections.emptyList();
        }
        return patternStrings.stream().map(Pattern::compile).collect(Collectors.toList());
    }

}
