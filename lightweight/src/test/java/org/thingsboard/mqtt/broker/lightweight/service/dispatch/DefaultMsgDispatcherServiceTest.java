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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;

import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultMsgDispatcherService} — verifies queue enqueue, drop on full, and
 * non-blocking dispatch semantics without starting the full Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultMsgDispatcherServiceTest {

    @Mock
    private SubscriptionRegistry subscriptionRegistry;

    @Mock
    private TbActorSystem actorSystem;

    @Mock
    private PublishMsgQueueFactory queueFactory;

    private SimpleMeterRegistry meterRegistry;
    private LinkedBlockingQueue<PublishMsg> testQueue;
    private DefaultMsgDispatcherService dispatcher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Small capacity queue for testability (capacity = 2)
        testQueue = new LinkedBlockingQueue<>(2);
        when(queueFactory.createQueue()).thenReturn(testQueue);
        when(subscriptionRegistry.getSubscriptions(any())).thenReturn(Collections.emptyList());

        dispatcher = new DefaultMsgDispatcherService(queueFactory, subscriptionRegistry, actorSystem, meterRegistry);
        // @Value not processed in plain JUnit — set consumerThreads explicitly
        ReflectionTestUtils.setField(dispatcher, "consumerThreads", 1);
        // start() initializes the queue reference and droppedMsgsCounter
        dispatcher.start();
        // stop() shuts down consumer threads so the queue won't drain during tests
        dispatcher.stop();
    }

    @AfterEach
    void tearDown() {
        // Dispatcher already stopped in setUp; nothing to do here
    }

    @Test
    void testDispatch_enqueuesMessage() {
        PublishMsg msg = buildMsg("test/topic");

        dispatcher.dispatch(msg);

        // Message should be in the queue (consumers are stopped)
        assertThat(testQueue).hasSize(1);
        assertThat(meterRegistry.counter("mqtt.dispatch.dropped.total").count()).isEqualTo(0.0);
    }

    @Test
    void testDispatch_queueFull_dropsAndCountsMessage() {
        // Fill queue to capacity (capacity = 2)
        dispatcher.dispatch(buildMsg("test/1"));
        dispatcher.dispatch(buildMsg("test/2"));

        // Third dispatch must be dropped — queue is at capacity, consumers are stopped
        dispatcher.dispatch(buildMsg("test/3-overflow"));

        assertThat(testQueue.size()).isEqualTo(2); // queue still at capacity
        assertThat(meterRegistry.counter("mqtt.dispatch.dropped.total").count()).isEqualTo(1.0);
    }

    @Test
    void testDispatch_doesNotBlock() throws Exception {
        // Fill queue to capacity so subsequent dispatch() must use offer() (non-blocking)
        dispatcher.dispatch(buildMsg("fill/1"));
        dispatcher.dispatch(buildMsg("fill/2"));

        // Dispatch on an over-capacity queue — must complete without hanging
        AtomicBoolean completed = new AtomicBoolean(false);
        Thread dispatchThread = new Thread(() -> {
            dispatcher.dispatch(buildMsg("overflow/3"));
            completed.set(true);
        });

        dispatchThread.start();
        dispatchThread.join(500); // wait up to 500ms

        assertThat(completed.get()).isTrue(); // must complete without blocking
    }

    private PublishMsg buildMsg(String topic) {
        return PublishMsg.builder()
                .topicName(topic)
                .qos(0)
                .payload("test".getBytes())
                .retain(false)
                .dup(false)
                .packetId(0)
                .build();
    }

}
