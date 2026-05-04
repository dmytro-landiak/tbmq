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
package org.thingsboard.mqtt.broker.lightweight.session;

import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.exception.ProtocolViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicAliasCtxTest {

    @Test
    void zeroAliasReturnsNullWithoutValidation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertNull(ctx.getTopicNameByAlias("topic/a", 0));
    }

    @Test
    void firstUseStoresMappingAndReturnsTopicName() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertEquals("topic/a", ctx.getTopicNameByAlias("topic/a", 1));
        // Subsequent empty topic name resolves via the stored mapping
        assertEquals("topic/a", ctx.getTopicNameByAlias("", 1));
    }

    @Test
    void aliasZeroIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(0));
    }

    @Test
    void aliasExceedingInboundMaxIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(5, 5);
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(6));
    }

    @Test
    void disabledContextRejectsAnyAliasAsProtocolViolation() {
        TopicAliasCtx ctx = TopicAliasCtx.DISABLED_TOPIC_ALIASES;
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(1));
    }

    @Test
    void unknownAliasOnEmptyTopicIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertThrows(ProtocolViolationException.class, () -> ctx.getTopicNameByAlias("", 5));
    }

    @Test
    void validResolvedMappingDoesNotThrow() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        ctx.getTopicNameByAlias("foo", 1);
        assertDoesNotThrow(() -> ctx.getTopicNameByAlias("", 1));
    }
}
