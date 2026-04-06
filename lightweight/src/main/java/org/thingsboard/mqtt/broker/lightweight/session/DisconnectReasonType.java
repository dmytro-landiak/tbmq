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
package org.thingsboard.mqtt.broker.lightweight.session;

/**
 * Classifies the reason a client session was disconnected.
 *
 * <p>The {@link #allowsLastWillOnDisconnect()} method determines whether the broker
 * should publish the client's Last Will and Testament message upon disconnect.
 * Per MQTT 3.1.1 spec section 3.1.2.5: LWT is published when the connection is
 * closed without the client first sending a DISCONNECT packet.
 *
 * <p>CRITICAL: {@link #ON_CONFLICTING_SESSIONS} MUST return {@code false} from
 * {@code allowsLastWillOnDisconnect()} — client takeover is a server-initiated
 * disconnect that should not trigger LWT (the client will reconnect immediately).
 */
public enum DisconnectReasonType {

    /** Client sent a clean DISCONNECT packet. No LWT. */
    ON_DISCONNECT_MSG(false),

    /** Client was displaced by a newer connection with the same clientId (takeover). No LWT. */
    ON_CONFLICTING_SESSIONS(false),

    /** Keep-alive timer expired — no PINGREQ received in time. LWT fires. */
    ON_KEEP_ALIVE(true),

    /** Underlying TCP/WebSocket channel closed unexpectedly. LWT fires. */
    ON_CHANNEL_CLOSED(true),

    /** Broker detected a protocol violation (e.g., invalid packet type sequence). LWT fires. */
    ON_PROTOCOL_ERROR(true),

    /** Incoming PUBLISH payload exceeds the configured maximum. LWT fires. */
    ON_PACKET_TOO_LARGE(true),

    /** MQTT decoder reported a malformed packet. LWT fires. */
    ON_MALFORMED_PACKET(true),

    /** General unclassified error during message processing. LWT fires. */
    ON_ERROR(true);

    private final boolean lastWillAllowed;

    DisconnectReasonType(boolean lastWillAllowed) {
        this.lastWillAllowed = lastWillAllowed;
    }

    /**
     * Returns {@code true} if the broker should publish the client's Last Will message
     * when disconnecting for this reason.
     */
    public boolean allowsLastWillOnDisconnect() {
        return lastWillAllowed;
    }

}
