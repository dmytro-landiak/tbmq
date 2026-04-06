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
package org.thingsboard.mqtt.broker.lightweight.actors.client.msg;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.thingsboard.mqtt.broker.lightweight.actors.MsgType;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;

/**
 * Instructs the {@link org.thingsboard.mqtt.broker.lightweight.actors.client.ClientActor}
 * to deliver a message to its client.
 *
 * <p>The {@code deliveryQos} is the effective QoS for delivery — already downgraded to
 * {@code min(publishQoS, subscriptionQoS)} by the publisher's actor.
 */
@Getter
@RequiredArgsConstructor
public class DeliverMsg implements TbActorMsg {

    /** The message to deliver to the subscriber. */
    private final PublishMsg publishMsg;

    /** Effective delivery QoS — min(publishQoS, subscriptionQoS). */
    private final int deliveryQos;

    @Override
    public MsgType getMsgType() {
        return MsgType.DELIVER_MSG;
    }

}
