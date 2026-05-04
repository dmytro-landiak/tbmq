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
package org.thingsboard.mqtt.broker.lightweight.util;

import io.netty.handler.codec.mqtt.MqttReasonCodes;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MqttReasonCodeResolverTest {

    @Test
    void disconnectReasonForProtocolError() {
        assertEquals(MqttReasonCodes.Disconnect.PROTOCOL_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_PROTOCOL_ERROR));
    }

    @Test
    void disconnectReasonForMalformedPacket() {
        assertEquals(MqttReasonCodes.Disconnect.PROTOCOL_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_MALFORMED_PACKET));
    }

    @Test
    void disconnectReasonForPacketTooLarge() {
        assertEquals(MqttReasonCodes.Disconnect.PACKET_TOO_LARGE,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_PACKET_TOO_LARGE));
    }

    @Test
    void disconnectReasonForUnspecified() {
        assertEquals(MqttReasonCodes.Disconnect.UNSPECIFIED_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_ERROR));
        assertEquals(MqttReasonCodes.Disconnect.UNSPECIFIED_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_KEEP_ALIVE));
    }
}
