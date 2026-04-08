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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentMapSubscriptionTrieTest {

    private ConcurrentMapSubscriptionTrie<String> trie;

    @BeforeEach
    void setUp() {
        trie = new ConcurrentMapSubscriptionTrie<>();
        trie.setWaitForClearLockMs(5000);
    }

    @Test
    void testGet_exactMatch() {
        trie.put("sensor/temp", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temp");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
        assertThat(result.get(0).getTopicFilter()).isEqualTo("sensor/temp");
    }

    @Test
    void testGet_singleLevelWildcard() {
        trie.put("sensor/+", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temp");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
        assertThat(result.get(0).getTopicFilter()).isEqualTo("sensor/+");
    }

    @Test
    void testGet_singleLevelWildcardNoMatchDeeper() {
        trie.put("sensor/+", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temp/value");
        assertThat(result).isEmpty();
    }

    @Test
    void testGet_multiLevelWildcard() {
        trie.put("sensor/#", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temp");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
        assertThat(result.get(0).getTopicFilter()).isEqualTo("sensor/#");
    }

    @Test
    void testGet_multiLevelWildcardMatchesDeeper() {
        trie.put("sensor/#", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temp/value");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
    }

    @Test
    void testGet_multiLevelWildcardRoot() {
        trie.put("#", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("any/topic");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
    }

    @Test
    void testGet_sysTopicExcludedFromHashWildcard() {
        trie.put("#", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("$SYS/broker/uptime");
        assertThat(result).isEmpty();
    }

    @Test
    void testGet_sysTopicExcludedFromPlusWildcard() {
        trie.put("+/broker", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("$SYS/broker");
        assertThat(result).isEmpty();
    }

    @Test
    void testGet_explicitSysSubscriptionWorks() {
        trie.put("$SYS/broker/+", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("$SYS/broker/uptime");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("sub1");
    }

    @Test
    void testGet_multipleSubscribers() {
        trie.put("topic", "sub1");
        trie.put("topic", "sub2");
        List<ValueWithTopicFilter<String>> result = trie.get("topic");
        assertThat(result).hasSize(2);
        List<String> values = result.stream().map(ValueWithTopicFilter::getValue).collect(Collectors.toList());
        assertThat(values).containsExactlyInAnyOrder("sub1", "sub2");
    }

    @Test
    void testDelete_removesSubscription() {
        trie.put("topic", "sub1");
        boolean deleted = trie.delete("topic", val -> val.equals("sub1"));
        assertThat(deleted).isTrue();
        List<ValueWithTopicFilter<String>> result = trie.get("topic");
        assertThat(result).isEmpty();
    }

    @Test
    void testGet_valueWithTopicFilterCarriesCorrectFilter() {
        trie.put("sensor/+", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("sensor/temperature");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTopicFilter()).isEqualTo("sensor/+");
    }

    @Test
    void testGet_emptyTopicNoNpe() {
        trie.put("topic", "sub1");
        List<ValueWithTopicFilter<String>> result = trie.get("");
        // empty topic string should not throw NPE, may return empty or no match
        assertThat(result).isNotNull();
    }

}
