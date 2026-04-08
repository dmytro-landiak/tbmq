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
package org.thingsboard.mqtt.broker.lightweight.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.storage.rocksdb.RocksDbColumnFamily;
import org.thingsboard.mqtt.broker.lightweight.storage.rocksdb.RocksDbStorage;

/**
 * RocksDB-backed implementation of {@link LightweightCredentialService} with Caffeine caching.
 *
 * <p>Credential objects are JSON-serialized (via Jackson) and stored in the RocksDB
 * {@code CREDENTIALS} column family. Cache lookups use the credential ID as the cache key.
 *
 * <p>Cache strategy:
 * <ul>
 *   <li>{@code findByCredentialId} — cached with {@code unless = "#result == null"} to prevent
 *       caching null values (prevents cache poisoning from nonexistent key lookups).</li>
 *   <li>{@code saveCredential} — evicts the cache entry for the saved credential ID.</li>
 *   <li>{@code deleteCredential} — evicts the cache entry for the deleted credential ID.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLightweightCredentialService implements LightweightCredentialService {

    private final RocksDbStorage storage;
    private final ObjectMapper objectMapper;

    @Override
    @Cacheable(value = "credentials", key = "#credentialId", unless = "#result == null")
    public LightweightCredential findByCredentialId(String credentialId) {
        String json = storage.get(RocksDbColumnFamily.CREDENTIALS, credentialId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LightweightCredential.class);
        } catch (Exception e) {
            log.error("Failed to deserialize credential for id: {}", credentialId, e);
            return null;
        }
    }

    @Override
    @CacheEvict(value = "credentials", key = "#credential.credentialId")
    public void saveCredential(LightweightCredential credential) {
        try {
            String json = objectMapper.writeValueAsString(credential);
            storage.put(RocksDbColumnFamily.CREDENTIALS, credential.getCredentialId(), json);
            log.debug("Saved credential: id={}, type={}", credential.getCredentialId(), credential.getType());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize credential: " + credential.getCredentialId(), e);
        }
    }

    @Override
    @CacheEvict(value = "credentials", key = "#credentialId")
    public void deleteCredential(String credentialId) {
        storage.delete(RocksDbColumnFamily.CREDENTIALS, credentialId);
        log.debug("Deleted credential: id={}", credentialId);
    }

}
