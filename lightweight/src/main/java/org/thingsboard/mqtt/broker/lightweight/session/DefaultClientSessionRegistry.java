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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of {@link ClientSessionRegistry}.
 *
 * <p>Uses a {@link ConcurrentHashMap} for lock-free concurrent reads.
 * All operations are atomic at the map level.
 */
@Slf4j
@Service
public class DefaultClientSessionRegistry implements ClientSessionRegistry {

    private final ConcurrentHashMap<String, ClientSessionCtx> sessions = new ConcurrentHashMap<>();

    @Override
    public ClientSessionCtx getSession(String clientId) {
        return sessions.get(clientId);
    }

    @Override
    public ClientSessionCtx registerSession(String clientId, ClientSessionCtx ctx) {
        log.debug("[{}] Registering session", clientId);
        return sessions.put(clientId, ctx);
    }

    @Override
    public ClientSessionCtx removeSession(String clientId) {
        log.debug("[{}] Removing session", clientId);
        return sessions.remove(clientId);
    }

    @Override
    public int getSessionCount() {
        return sessions.size();
    }

}
