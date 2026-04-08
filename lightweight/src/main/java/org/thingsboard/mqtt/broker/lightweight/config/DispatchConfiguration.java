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
package org.thingsboard.mqtt.broker.lightweight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.ConcurrentMapRetainMsgTrie;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsg;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.ConcurrentMapSubscriptionTrie;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.Subscription;

/**
 * Spring configuration for the dispatch layer.
 *
 * <p>Registers the trie data structures as singleton Spring beans so they can be
 * injected into {@link org.thingsboard.mqtt.broker.lightweight.service.subscription.DefaultSubscriptionRegistry}
 * and {@link org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.DefaultRetainedMsgService}.
 * The trie implementations are plain POJOs (no {@code @Service} annotation) — they are created here
 * to allow {@code @Value} injection via Lombok {@code @Setter}.
 */
@Configuration
public class DispatchConfiguration {

    @Bean
    public ConcurrentMapSubscriptionTrie<Subscription> subscriptionTrie() {
        return new ConcurrentMapSubscriptionTrie<>();
    }

    @Bean
    public ConcurrentMapRetainMsgTrie<RetainedMsg> retainMsgTrie() {
        return new ConcurrentMapRetainMsgTrie<>();
    }

}
