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
package org.thingsboard.mqtt.broker.lightweight.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.thingsboard.mqtt.broker.lightweight.security.auth.CredentialType;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredential;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredentialService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link LightweightCredentialService}.
 * Tests RocksDB-backed save/find/delete operations and cache eviction.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-credentials-${random.uuid}",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CredentialServiceTest {

    @Autowired
    private LightweightCredentialService credentialService;

    @Test
    void saveAndFindCredential_returnsCorrectCredential() {
        // Given
        LightweightCredential credential = new LightweightCredential(
                "testuser", CredentialType.BASIC, "{\"password\":\"$2a$10$hash\"}");

        // When
        credentialService.saveCredential(credential);
        LightweightCredential found = credentialService.findByCredentialId("testuser");

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getCredentialId()).isEqualTo("testuser");
        assertThat(found.getType()).isEqualTo(CredentialType.BASIC);
        assertThat(found.getCredentialValue()).isEqualTo("{\"password\":\"$2a$10$hash\"}");
    }

    @Test
    void findByCredentialId_nonexistent_returnsNull() {
        // When
        LightweightCredential found = credentialService.findByCredentialId("nonexistent");

        // Then
        assertThat(found).isNull();
    }

    @Test
    void deleteCredential_thenFindReturnsNull() {
        // Given
        LightweightCredential credential = new LightweightCredential(
                "deleteuser", CredentialType.BASIC, "{\"password\":\"$2a$10$hash\"}");
        credentialService.saveCredential(credential);

        // Verify it was saved
        assertThat(credentialService.findByCredentialId("deleteuser")).isNotNull();

        // When
        credentialService.deleteCredential("deleteuser");

        // Then
        assertThat(credentialService.findByCredentialId("deleteuser")).isNull();
    }

}
