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
 * Registry of active client sessions indexed by clientId.
 *
 * <p>Thread-safe operations for session lifecycle management.
 * All active (CONNECTED) sessions are stored here; sessions are removed
 * on DISCONNECT or channel close.
 */
public interface ClientSessionRegistry {

    /**
     * Returns the session for the given clientId, or {@code null} if not found.
     */
    ClientSessionCtx getSession(String clientId);

    /**
     * Registers a new session for the given clientId.
     *
     * @param clientId the MQTT clientId
     * @param ctx      the session context
     * @return the previous session for this clientId, or {@code null} if none existed
     */
    ClientSessionCtx registerSession(String clientId, ClientSessionCtx ctx);

    /**
     * Removes the session for the given clientId.
     *
     * @param clientId the MQTT clientId
     * @return the removed session, or {@code null} if none existed
     */
    ClientSessionCtx removeSession(String clientId);

    /**
     * Returns the total number of active sessions.
     */
    int getSessionCount();

}
