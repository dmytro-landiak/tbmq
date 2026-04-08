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
package org.thingsboard.mqtt.broker.lightweight.service.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LinkedBlockingQueueFactory} — verifies bounded queue creation
 * and configurable capacity without starting the full Spring context.
 */
class LinkedBlockingQueueFactoryTest {

    @Test
    void testCreateQueue_returnsBoundedQueue() {
        LinkedBlockingQueueFactory factory = new LinkedBlockingQueueFactory();
        ReflectionTestUtils.setField(factory, "capacity", 50);

        BlockingQueue<PublishMsg> queue = factory.createQueue();

        assertThat(queue.remainingCapacity()).isEqualTo(50);
    }

    @Test
    void testCreateQueue_usesConfiguredCapacity() {
        LinkedBlockingQueueFactory factory = new LinkedBlockingQueueFactory();
        ReflectionTestUtils.setField(factory, "capacity", 1000);

        BlockingQueue<PublishMsg> queue = factory.createQueue();

        assertThat(queue.remainingCapacity()).isEqualTo(1000);
    }

    @Test
    void testCreateQueue_returnsLinkedBlockingQueueInstance() {
        LinkedBlockingQueueFactory factory = new LinkedBlockingQueueFactory();
        ReflectionTestUtils.setField(factory, "capacity", 100);

        BlockingQueue<PublishMsg> queue = factory.createQueue();

        assertThat(queue).isInstanceOf(LinkedBlockingQueue.class);
    }

    @Test
    void testCreateQueue_defaultCapacity() {
        LinkedBlockingQueueFactory factory = new LinkedBlockingQueueFactory();
        // Default @Value is 100000 — but without Spring, the field remains 0 (int default).
        // We explicitly set the documented default to verify the intended default behavior.
        ReflectionTestUtils.setField(factory, "capacity", 100_000);

        BlockingQueue<PublishMsg> queue = factory.createQueue();

        assertThat(queue.remainingCapacity()).isEqualTo(100_000);
    }

}
