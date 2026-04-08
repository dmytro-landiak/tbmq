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

/**
 * Service for CRUD operations on MQTT client credentials stored in RocksDB.
 *
 * <p>Credentials are keyed by {@link LightweightCredential#getCredentialId()} and are
 * backed by the RocksDB {@code CREDENTIALS} column family with Caffeine caching.
 */
public interface LightweightCredentialService {

    /**
     * Finds a credential by its ID.
     *
     * @param credentialId the credential identifier (username for BASIC, CN for SSL)
     * @return the credential, or {@code null} if not found
     */
    LightweightCredential findByCredentialId(String credentialId);

    /**
     * Saves (creates or updates) a credential in RocksDB.
     * Evicts any cached entry for the credential ID.
     *
     * @param credential the credential to save
     */
    void saveCredential(LightweightCredential credential);

    /**
     * Deletes a credential by ID from RocksDB.
     * Evicts any cached entry for the credential ID.
     *
     * @param credentialId the credential identifier to delete
     */
    void deleteCredential(String credentialId);

}
