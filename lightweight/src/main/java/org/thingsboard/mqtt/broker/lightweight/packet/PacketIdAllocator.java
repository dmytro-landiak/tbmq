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
package org.thingsboard.mqtt.broker.lightweight.packet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session MQTT packet identifier allocator.
 *
 * <p>Assigns unique packet IDs in the range [1, 65535] as required by the MQTT 3.1.1
 * specification (section 2.3.1). Packet ID 0 is reserved and never allocated.
 *
 * <p>This allocator is per-session (one instance per {@code ClientSessionCtx}) and is
 * NOT shared across clients.
 */
public class PacketIdAllocator {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final Set<Integer> inUse = ConcurrentHashMap.newKeySet();

    /**
     * Allocates the next available packet ID.
     *
     * @return a unique packet ID in [1, 65535]
     * @throws IllegalStateException if all 65535 packet IDs are currently in-flight
     */
    public int nextPacketId() {
        for (int i = 0; i < 65535; i++) {
            int id = (counter.incrementAndGet() & 0xFFFF);
            if (id == 0) {
                id = (counter.incrementAndGet() & 0xFFFF);
            }
            if (inUse.add(id)) {
                return id;
            }
        }
        throw new IllegalStateException("No available packet IDs (65535 in-flight)");
    }

    /**
     * Releases a packet ID back to the pool after the QoS flow completes.
     *
     * @param id the packet ID to release
     */
    public void releasePacketId(int id) {
        inUse.remove(id);
    }

    /**
     * Releases all in-flight packet IDs (used on session cleanup).
     */
    public void releaseAll() {
        inUse.clear();
    }

}
