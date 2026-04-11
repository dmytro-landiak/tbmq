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
import io.micrometer.core.instrument.Gauge;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Per-shared-group round-robin counters. Key: shareName, Value: monotonic counter. */
    private final ConcurrentHashMap<String, AtomicInteger> sharedGroupCounters = new ConcurrentHashMap<>();

    @Override
    public void dispatch(PublishMsg msg) {
        if (!running || queue == null) {
            log.debug("[{}] Dispatcher not running — message dropped during startup/shutdown",
                    msg.getTopicName());
            return;
        }
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

        Gauge.builder("mqtt.dispatch.queue.depth", queue, Queue::size)
                .description("Current number of messages waiting in dispatch queue")
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
        // Best-effort drain: stop accepting new work, give consumers time to empty queue
        consumerPool.shutdown();
        try {
            if (!consumerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Dispatch consumer pool did not drain within 5s — {} messages may be lost",
                        queue.size());
                consumerPool.shutdownNow();
                consumerPool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumerPool.shutdownNow();
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

        // Separate shared from non-shared subscriptions (per D-10)
        Map<String, List<Subscription>> sharedGroups = new HashMap<>();
        List<Subscription> nonShared = new ArrayList<>();
        for (ValueWithTopicFilter<Subscription> match : matches) {
            Subscription sub = match.getValue();
            if (sub.getSessionCtx().getState() != SessionState.CONNECTED) {
                continue;
            }
            if (sub.getShareName() != null) {
                sharedGroups.computeIfAbsent(sub.getShareName(), k -> new ArrayList<>()).add(sub);
            } else {
                nonShared.add(sub);
            }
        }

        // Deliver to all non-shared subscribers
        for (Subscription sub : nonShared) {
            deliverToSubscriber(msg, sub);
        }

        // For each shared group, pick one via round-robin (per D-09)
        for (Map.Entry<String, List<Subscription>> entry : sharedGroups.entrySet()) {
            Subscription picked = pickFromSharedGroup(entry.getKey(), entry.getValue());
            if (picked != null) {
                deliverToSubscriber(msg, picked);
            }
        }
    }

    private void deliverToSubscriber(PublishMsg msg, Subscription sub) {
        int deliveryQos = Math.min(msg.getQos(), sub.getQos());
        PublishMsg deliveryMsg = PublishMsg.builder()
                .topicName(msg.getTopicName())
                .qos(deliveryQos)
                .payload(msg.getPayload())
                .retain(false)
                .dup(false)
                .packetId(0)
                .properties(msg.getProperties())
                .build();
        DeliverMsg deliverMsg = new DeliverMsg(deliveryMsg, deliveryQos, sub.getSubscriptionId());
        try {
            actorSystem.tell(new TbTypeActorId("client", sub.getClientId()), deliverMsg);
        } catch (Exception e) {
            log.trace("[{}] Failed to deliver message — actor may have been destroyed", sub.getClientId(), e);
        }
    }

    private Subscription pickFromSharedGroup(String groupKey, List<Subscription> members) {
        AtomicInteger counter = sharedGroupCounters.computeIfAbsent(groupKey, k -> new AtomicInteger(0));
        int size = members.size();
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(counter.getAndIncrement(), size);
            Subscription sub = members.get(index);
            if (sub.getSessionCtx().getState() == SessionState.CONNECTED) {
                return sub;
            }
        }
        return null; // no connected member found
    }

}
