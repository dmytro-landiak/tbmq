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

import org.thingsboard.mqtt.broker.lightweight.actors.MsgType;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;

/**
 * Sent to the {@link org.thingsboard.mqtt.broker.lightweight.actors.client.ClientActor}
 * when the client sends a PINGREQ packet.
 *
 * <p>The actor responds by writing PINGRESP back to the client channel.
 * No payload — PINGREQ carries no data per spec.
 */
public class PingMsg implements TbActorMsg {

    public static final PingMsg INSTANCE = new PingMsg();

    private PingMsg() {
    }

    @Override
    public MsgType getMsgType() {
        return MsgType.PING_MSG;
    }

}
