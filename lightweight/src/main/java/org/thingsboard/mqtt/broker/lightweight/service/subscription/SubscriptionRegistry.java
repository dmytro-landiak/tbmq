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

import java.util.Set;

/**
 * Registry for MQTT topic subscriptions.
 *
 * <p>Provides exact-match topic lookup only in Phase 2 (D-06).
 * Wildcard subscription matching is deferred to Phase 3 (subscription trie).
 */
public interface SubscriptionRegistry {

    /**
     * Adds or updates a subscription for the given topic filter.
     * If the client already has a subscription for this topic, it is replaced (QoS update).
     *
     * @param topicFilter the MQTT topic filter (may contain wildcards in Phase 3+)
     * @param subscription the subscription to register
     */
    void subscribe(String topicFilter, Subscription subscription);

    /**
     * Removes a client's subscription for the given topic filter.
     *
     * @param topicFilter the MQTT topic filter
     * @param clientId    the client to unsubscribe
     */
    void unsubscribe(String topicFilter, String clientId);

    /**
     * Returns all subscriptions for an exact topic name match.
     *
     * <p>Per D-06, wildcard expansion is NOT performed in Phase 2.
     * Only subscriptions whose topic filter exactly equals {@code topicName} are returned.
     *
     * @param topicName the exact MQTT topic name from a PUBLISH packet
     * @return set of matching subscriptions (never null, may be empty)
     */
    Set<Subscription> getSubscriptions(String topicName);

    /**
     * Removes all subscriptions for the given client across all topic filters.
     * Used during client disconnect to clean up subscription state.
     *
     * @param clientId the client whose subscriptions should be removed
     */
    void removeAllSubscriptions(String clientId);

}
