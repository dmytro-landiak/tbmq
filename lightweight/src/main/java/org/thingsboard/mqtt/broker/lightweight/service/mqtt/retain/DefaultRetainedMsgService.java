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
package org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * In-memory implementation of {@link RetainedMsgService} backed by {@link ConcurrentMapRetainMsgTrie}.
 *
 * <p>Per D-09: retained messages are stored in memory for the broker lifetime only.
 * No persistence across restarts in R1 — this is intentional for the lightweight variant.
 *
 * <p>Wildcard lookup (Phase 3): {@link #getRetainedMessages(String)} delegates to the trie
 * which supports + and # wildcards per MQTT 3.1.1 spec.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultRetainedMsgService implements RetainedMsgService {

    private final ConcurrentMapRetainMsgTrie<RetainedMsg> retainMsgTrie;

    @Override
    public void setRetainedMessage(String topic, RetainedMsg msg) {
        retainMsgTrie.put(topic, msg);
        log.debug("Stored retained message for topic '{}' (qos={}, {} bytes)", topic, msg.getQos(), msg.getPayload().length);
    }

    @Override
    public void clearRetainedMessage(String topic) {
        retainMsgTrie.delete(topic);
        log.debug("Cleared retained message for topic '{}'", topic);
    }

    @Override
    public Optional<RetainedMsg> getRetainedMessage(String topic) {
        List<RetainedMsg> results = retainMsgTrie.get(topic);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<RetainedMsg> getRetainedMessages(String topicFilter) {
        return retainMsgTrie.get(topicFilter);
    }

}
