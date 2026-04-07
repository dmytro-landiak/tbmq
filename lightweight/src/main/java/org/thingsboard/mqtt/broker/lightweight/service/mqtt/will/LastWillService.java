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
package org.thingsboard.mqtt.broker.lightweight.service.mqtt.will;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages Last Will and Testament (LWT) messages per MQTT 3.1.1 spec section 3.1.2.5.
 *
 * <p>LWT lifecycle:
 * <ol>
 *   <li>Stored at CONNECT time when will flag is set, keyed by session UUID</li>
 *   <li>Removed with delivery on ungraceful disconnect (keep-alive, channel close, error)</li>
 *   <li>Removed without delivery on clean DISCONNECT or client takeover</li>
 * </ol>
 *
 * <p>Keyed by session UUID (not clientId) to handle client takeover correctly:
 * the old session has a different UUID from the new session, so the old will
 * is suppressed independently of the new session's will.
 */
public interface LastWillService {

    /**
     * Stores the will message for the given session.
     * Called during CONNECT processing when the will flag is set.
     *
     * @param sessionId   the unique session UUID
     * @param willMessage the will message to store
     */
    void storeWill(UUID sessionId, WillMessage willMessage);

    /**
     * Removes and returns the will message for the given session, for delivery.
     * Called on ungraceful disconnect (keep-alive expiry, channel close, error).
     *
     * @param sessionId the unique session UUID
     * @return an Optional containing the will message to deliver, or empty if none
     */
    Optional<WillMessage> removeWill(UUID sessionId);

    /**
     * Removes the will message for the given session without delivering it.
     * Called on clean DISCONNECT or client takeover.
     *
     * @param sessionId the unique session UUID
     */
    void removeWillWithoutDelivery(UUID sessionId);

}
