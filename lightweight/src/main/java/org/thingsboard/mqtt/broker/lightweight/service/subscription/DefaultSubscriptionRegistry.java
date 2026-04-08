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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory subscription registry backed by {@link ConcurrentMapSubscriptionTrie}.
 *
 * <p>Provides wildcard subscription matching (+ and #) via the trie data structure.
 * The per-client topic filter index ({@code clientSubscriptions}) enables efficient
 * {@link #removeAllSubscriptions(String)} without a full trie scan.
 *
 * <p>Thread safety: the trie is inherently concurrent; the per-client map uses
 * {@link ConcurrentHashMap} with {@link ConcurrentHashMap#newKeySet()} for thread-safe sets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSubscriptionRegistry implements SubscriptionRegistry {

    private final ConcurrentMapSubscriptionTrie<Subscription> subscriptionTrie;

    /** Per-client index: clientId -> set of subscribed topic filters. */
    private final ConcurrentHashMap<String, Set<String>> clientSubscriptions = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String topicFilter, Subscription subscription) {
        subscriptionTrie.put(topicFilter, subscription);
        clientSubscriptions.computeIfAbsent(subscription.getClientId(), k -> ConcurrentHashMap.newKeySet())
                .add(topicFilter);
        log.debug("[{}] Registered subscription for topic '{}' with QoS {}",
                subscription.getClientId(), topicFilter, subscription.getQos());
    }

    @Override
    public void unsubscribe(String topicFilter, String clientId) {
        subscriptionTrie.delete(topicFilter, sub -> sub.getClientId().equals(clientId));
        Set<String> filters = clientSubscriptions.get(clientId);
        if (filters != null) {
            filters.remove(topicFilter);
        }
        log.debug("[{}] Removed subscription for topic '{}'", clientId, topicFilter);
    }

    @Override
    public List<ValueWithTopicFilter<Subscription>> getSubscriptions(String topicName) {
        return subscriptionTrie.get(topicName);
    }

    @Override
    public void removeAllSubscriptions(String clientId) {
        Set<String> filters = clientSubscriptions.remove(clientId);
        if (filters != null) {
            for (String filter : filters) {
                subscriptionTrie.delete(filter, sub -> sub.getClientId().equals(clientId));
            }
        }
        log.debug("[{}] Removed all subscriptions on disconnect", clientId);
    }

}
