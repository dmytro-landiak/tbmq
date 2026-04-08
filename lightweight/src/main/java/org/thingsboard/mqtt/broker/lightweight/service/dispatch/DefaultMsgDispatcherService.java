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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.actors.TbTypeActorId;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.Subscription;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.ValueWithTopicFilter;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.DeliverMsg;
import org.thingsboard.mqtt.broker.lightweight.session.SessionState;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue-backed implementation of {@link MsgDispatcherService}.
 *
 * <p>Published messages are enqueued via {@link #dispatch(PublishMsg)} and consumed by a fixed
 * thread pool. Each consumer thread performs wildcard trie lookup via {@link SubscriptionRegistry}
 * and delivers to matching subscriber actors via {@link TbActorSystem#tell(org.thingsboard.mqtt.broker.lightweight.actors.TbActorId, org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg)}.
 *
 * <p>Backpressure: when the queue is full, messages are dropped and the {@code mqtt.dispatch.dropped.total}
 * counter is incremented. The dispatcher never blocks the caller (per D-01/D-04).
 *
 * <p>Lifecycle: implements {@link SmartLifecycle} for graceful startup and shutdown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultMsgDispatcherService implements MsgDispatcherService, SmartLifecycle {

    private final PublishMsgQueueFactory queueFactory;
    private final SubscriptionRegistry subscriptionRegistry;
    private final TbActorSystem actorSystem;
    private final MeterRegistry meterRegistry;

    @Value("${tbmq.dispatch.consumer-threads:2}")
    private int consumerThreads;

    private BlockingQueue<PublishMsg> queue;
    private ExecutorService consumerPool;
    private Counter droppedMsgsCounter;
    private volatile boolean running = false;

    @Override
    public void dispatch(PublishMsg msg) {
        if (!queue.offer(msg)) {
            droppedMsgsCounter.increment();
            log.debug("[{}] Dispatch queue full -- message dropped", msg.getTopicName());
        }
    }

    @Override
    public void start() {
        queue = queueFactory.createQueue();
        droppedMsgsCounter = Counter.builder("mqtt.dispatch.dropped.total")
                .description("Messages dropped due to full dispatch queue")
                .register(meterRegistry);

        AtomicInteger threadNum = new AtomicInteger(0);
        consumerPool = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "dispatch-consumer-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < consumerThreads; i++) {
            consumerPool.submit(this::consumeLoop);
        }

        running = true;
        log.info("Message dispatcher started with {} consumer threads and queue capacity {}", consumerThreads, queue.remainingCapacity() + queue.size());
    }

    @Override
    public void stop() {
        running = false;
        consumerPool.shutdownNow();
        try {
            consumerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Message dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PublishMsg msg = queue.take();
                deliverToSubscribers(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in dispatch consumer loop", e);
            }
        }
    }

    private void deliverToSubscribers(PublishMsg msg) {
        List<ValueWithTopicFilter<Subscription>> matches = subscriptionRegistry.getSubscriptions(msg.getTopicName());
        for (ValueWithTopicFilter<Subscription> match : matches) {
            Subscription sub = match.getValue();
            if (sub.getSessionCtx().getState() != SessionState.CONNECTED) {
                continue;
            }
            int deliveryQos = Math.min(msg.getQos(), sub.getQos());
            PublishMsg deliveryMsg = PublishMsg.builder()
                    .topicName(msg.getTopicName())
                    .qos(deliveryQos)
                    .payload(msg.getPayload())
                    .retain(false)
                    .dup(false)
                    .packetId(0)
                    .build();
            DeliverMsg deliverMsg = new DeliverMsg(deliveryMsg, deliveryQos);
            try {
                actorSystem.tell(new TbTypeActorId("client", sub.getClientId()), deliverMsg);
            } catch (Exception e) {
                // Actor may be destroyed during disconnect race — log at TRACE and continue
                log.trace("[{}] Failed to deliver message — actor may have been destroyed", sub.getClientId(), e);
            }
        }
    }

}
