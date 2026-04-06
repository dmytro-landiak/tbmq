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
package org.thingsboard.mqtt.broker.lightweight.service.subscription;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory subscription registry backed by a {@link ConcurrentHashMap}.
 *
 * <p>Topic filter -> Set&lt;Subscription&gt; mapping. Exact-match only per D-06.
 * Wildcard subscription matching is deferred to Phase 3's subscription trie.
 *
 * <p>Thread safety: ConcurrentHashMap with ConcurrentHashMap.newKeySet() for subscription sets
 * ensures safe concurrent access without explicit locking.
 */
@Slf4j
@Service
public class DefaultSubscriptionRegistry implements SubscriptionRegistry {

    private final ConcurrentHashMap<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String topicFilter, Subscription subscription) {
        subscriptions.compute(topicFilter, (topic, existing) -> {
            Set<Subscription> set = (existing != null) ? existing : ConcurrentHashMap.newKeySet();
            // Remove any existing subscription for the same clientId (re-subscribe updates QoS)
            set.removeIf(s -> s.getClientId().equals(subscription.getClientId()));
            set.add(subscription);
            return set;
        });
        log.debug("[{}] Registered subscription for topic '{}' with QoS {}",
                subscription.getClientId(), topicFilter, subscription.getQos());
    }

    @Override
    public void unsubscribe(String topicFilter, String clientId) {
        subscriptions.computeIfPresent(topicFilter, (topic, set) -> {
            set.removeIf(s -> s.getClientId().equals(clientId));
            return set.isEmpty() ? null : set;
        });
        log.debug("[{}] Removed subscription for topic '{}'", clientId, topicFilter);
    }

    @Override
    public Set<Subscription> getSubscriptions(String topicName) {
        return subscriptions.getOrDefault(topicName, Collections.emptySet());
    }

    @Override
    public void removeAllSubscriptions(String clientId) {
        subscriptions.forEach((topic, set) -> {
            set.removeIf(s -> s.getClientId().equals(clientId));
        });
        // Clean up empty sets
        subscriptions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        log.debug("[{}] Removed all subscriptions on disconnect", clientId);
    }

}
