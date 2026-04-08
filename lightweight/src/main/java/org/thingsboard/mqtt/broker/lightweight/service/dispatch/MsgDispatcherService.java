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

import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;

/**
 * Dispatches a published message to all matching subscribers via the in-process queue.
 * Non-blocking — returns immediately after enqueueing or dropping.
 *
 * <p>Per D-01: publisher actors call this service instead of iterating subscribers inline.
 * Background consumer threads perform wildcard trie lookup and send DeliverMsg to subscriber actors.
 */
public interface MsgDispatcherService {

    /**
     * Enqueues the message for asynchronous delivery to all matching subscribers.
     *
     * <p>If the dispatch queue is full, the message is dropped and a dropped_msgs counter
     * is incremented. This method never blocks.
     *
     * @param msg the published message to dispatch
     */
    void dispatch(PublishMsg msg);

}
