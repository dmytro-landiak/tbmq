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

import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttReasonCodes;
import io.netty.handler.codec.mqtt.MqttVersion;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;

/**
 * Resolves version-appropriate MQTT reason codes for ACK packets.
 *
 * <p>MQTT 3.1.1 does not include reason code bytes in PUBACK/PUBREC/PUBREC/PUBCOMP/UNSUBACK
 * packets — only the packet identifier is present. For those cases, methods return {@code null}
 * to signal that the no-reason-code overload should be used by the caller.
 *
 * <p>For CONNACK, both MQTT 3.1.1 and 5.0 include a return code — the enum value differs.
 *
 * <p>All methods are static — no instance needed.
 */
public final class MqttReasonCodeResolver {

    private MqttReasonCodeResolver() {
    }

    // -------------------------------------------------------------------------
    // CONNACK reason codes
    // -------------------------------------------------------------------------

    /**
     * Returns the appropriate "Not Authorized" CONNACK return code.
     *
     * @return {@code CONNECTION_REFUSED_NOT_AUTHORIZED_5} for MQTT 5.0,
     *         {@code CONNECTION_REFUSED_NOT_AUTHORIZED} for MQTT 3.1.1
     */
    public static MqttConnectReturnCode connectionRefusedNotAuthorized(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5
                ? MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED_5
                : MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
    }

    /**
     * Returns the appropriate "Unspecified Error / Server Unavailable" CONNACK return code.
     *
     * @return {@code CONNECTION_REFUSED_UNSPECIFIED_ERROR} for MQTT 5.0,
     *         {@code CONNECTION_REFUSED_SERVER_UNAVAILABLE} for MQTT 3.1.1
     */
    public static MqttConnectReturnCode connectionRefusedUnspecified(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5
                ? MqttConnectReturnCode.CONNECTION_REFUSED_UNSPECIFIED_ERROR
                : MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE;
    }

    /**
     * Returns the appropriate "Bad Credentials" CONNACK return code.
     *
     * @return {@code CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD} for MQTT 5.0,
     *         {@code CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD} for MQTT 3.1.1
     */
    public static MqttConnectReturnCode connectionRefusedBadCredentials(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5
                ? MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD
                : MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
    }

    // -------------------------------------------------------------------------
    // PUBACK reason codes
    // -------------------------------------------------------------------------

    /**
     * Returns the PUBACK Success reason code for MQTT 5.0, or {@code null} for MQTT 3.1.1.
     *
     * <p>Callers should use the no-reason-code overload when this returns {@code null}.
     */
    public static MqttReasonCodes.PubAck pubAckSuccess(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? MqttReasonCodes.PubAck.SUCCESS : null;
    }

    /**
     * Returns the PUBACK Not Authorized reason code for MQTT 5.0, or {@code null} for MQTT 3.1.1.
     */
    public static MqttReasonCodes.PubAck pubAckNotAuthorized(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? MqttReasonCodes.PubAck.NOT_AUTHORIZED : null;
    }

    // -------------------------------------------------------------------------
    // PUBREC reason codes
    // -------------------------------------------------------------------------

    /**
     * Returns the PUBREC Success reason code for MQTT 5.0, or {@code null} for MQTT 3.1.1.
     */
    public static MqttReasonCodes.PubRec pubRecSuccess(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? MqttReasonCodes.PubRec.SUCCESS : null;
    }

    /**
     * Returns the PUBREC Not Authorized reason code for MQTT 5.0, or {@code null} for MQTT 3.1.1.
     */
    public static MqttReasonCodes.PubRec pubRecNotAuthorized(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? MqttReasonCodes.PubRec.NOT_AUTHORIZED : null;
    }

    // -------------------------------------------------------------------------
    // SUBACK reason codes
    // -------------------------------------------------------------------------

    /**
     * Returns the SUBACK Not Authorized reason code for MQTT 5.0, or {@code null} for MQTT 3.1.1.
     *
     * <p>For MQTT 3.1.1, callers should use the integer value {@code 0x80} directly
     * in the granted QoS list to indicate subscription failure.
     */
    public static MqttReasonCodes.SubAck subAckNotAuthorized(ClientSessionCtx ctx) {
        return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? MqttReasonCodes.SubAck.NOT_AUTHORIZED : null;
    }

    // -------------------------------------------------------------------------
    // DISCONNECT reason codes (MQTT 5.0 only — only sent to 5.0 clients)
    // -------------------------------------------------------------------------

    /**
     * Returns the DISCONNECT Protocol Error reason code.
     *
     * <p>This is only sent to MQTT 5.0 clients — callers must check the version before calling.
     */
    public static MqttReasonCodes.Disconnect disconnectProtocolError() {
        return MqttReasonCodes.Disconnect.PROTOCOL_ERROR;
    }

    /**
     * Returns the DISCONNECT Topic Alias Invalid reason code.
     *
     * <p>This is only sent to MQTT 5.0 clients — callers must check the version before calling.
     */
    public static MqttReasonCodes.Disconnect disconnectTopicAliasInvalid() {
        return MqttReasonCodes.Disconnect.TOPIC_ALIAS_INVALID;
    }

}
