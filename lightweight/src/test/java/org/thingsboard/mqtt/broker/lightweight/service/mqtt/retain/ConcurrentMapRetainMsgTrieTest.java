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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentMapRetainMsgTrieTest {

    private ConcurrentMapRetainMsgTrie<String> trie;

    @BeforeEach
    void setUp() {
        trie = new ConcurrentMapRetainMsgTrie<>();
        trie.setWaitForClearLockMs(5000);
    }

    @Test
    void testGet_exactLookup() {
        trie.put("sensor/temp", "msg1");
        List<String> result = trie.get("sensor/temp");
        assertThat(result).containsExactly("msg1");
    }

    @Test
    void testGet_singleLevelWildcardLookup() {
        trie.put("sensor/temp", "msg1");
        List<String> result = trie.get("sensor/+");
        assertThat(result).containsExactly("msg1");
    }

    @Test
    void testGet_multiLevelWildcardLookup() {
        trie.put("sensor/temp", "msg1");
        List<String> result = trie.get("sensor/#");
        assertThat(result).containsExactly("msg1");
    }

    @Test
    void testGet_deepMultiLevelWildcard() {
        trie.put("a/b/c", "msg1");
        List<String> result = trie.get("a/#");
        assertThat(result).containsExactly("msg1");
    }

    @Test
    void testGet_sysTopicExcludedFromHashFilter() {
        trie.put("$SYS/broker/uptime", "sys_msg");
        List<String> result = trie.get("#");
        assertThat(result).doesNotContain("sys_msg");
    }

    @Test
    void testGet_sysTopicExcludedFromPlusFilter() {
        trie.put("$SYS/broker", "sys_msg");
        List<String> result = trie.get("+/broker");
        assertThat(result).doesNotContain("sys_msg");
    }

    @Test
    void testGet_explicitSysFilterWorks() {
        trie.put("$SYS/broker/uptime", "sys_msg");
        List<String> result = trie.get("$SYS/broker/+");
        assertThat(result).containsExactly("sys_msg");
    }

    @Test
    void testDelete_removesMessage() {
        trie.put("topic", "msg1");
        trie.delete("topic");
        List<String> result = trie.get("topic");
        assertThat(result).isEmpty();
    }

    @Test
    void testPut_overwriteReplaces() {
        trie.put("topic", "msg1");
        trie.put("topic", "msg2");
        List<String> result = trie.get("topic");
        assertThat(result).containsExactly("msg2");
    }

    @Test
    void testSize_tracksCountCorrectly() {
        assertThat(trie.size()).isEqualTo(0);
        trie.put("topic/1", "msg1");
        assertThat(trie.size()).isEqualTo(1);
        trie.put("topic/2", "msg2");
        assertThat(trie.size()).isEqualTo(2);
        trie.delete("topic/1");
        assertThat(trie.size()).isEqualTo(1);
    }

    @Test
    void testGet_multipleTopicsMatchedByWildcard() {
        trie.put("a/1", "m1");
        trie.put("a/2", "m2");
        List<String> result = trie.get("a/+");
        assertThat(result).containsExactlyInAnyOrder("m1", "m2");
    }

}
