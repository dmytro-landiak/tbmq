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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Default {@link PublishMsgQueueFactory} implementation backed by a {@link LinkedBlockingQueue}.
 *
 * <p>Queue capacity is configurable via {@code tbmq.dispatch.queue-capacity} (env: {@code TBMQ_DISPATCH_QUEUE_CAPACITY}).
 * Default capacity is 100,000 messages per RESEARCH recommendation.
 */
@Slf4j
@Component
public class LinkedBlockingQueueFactory implements PublishMsgQueueFactory {

    @Value("${tbmq.dispatch.queue-capacity:100000}")
    private int capacity;

    @Override
    public BlockingQueue<PublishMsg> createQueue() {
        log.debug("Creating dispatch queue with capacity {}", capacity);
        return new LinkedBlockingQueue<>(capacity);
    }

}
