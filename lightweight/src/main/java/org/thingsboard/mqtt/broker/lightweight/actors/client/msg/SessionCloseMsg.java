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
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;

/**
 * Sent to the {@link org.thingsboard.mqtt.broker.lightweight.actors.client.ClientActor}
 * when the underlying Netty channel closes unexpectedly (TCP drop, error, keep-alive expiry).
 *
 * <p>Differs from {@link MqttDisconnectMsg} in that the channel is already closed
 * when this message is processed — the actor only needs to clean up session state.
 */
@Getter
@RequiredArgsConstructor
public class SessionCloseMsg implements TbActorMsg {

    private final DisconnectReasonType reasonType;

    @Override
    public MsgType getMsgType() {
        return MsgType.DISCONNECT_MSG;
    }

}
