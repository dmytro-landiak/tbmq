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
package org.thingsboard.mqtt.broker.lightweight.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.security.auth.BasicMqttCredentials;
import org.thingsboard.mqtt.broker.lightweight.security.auth.CredentialType;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredential;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightCredentialService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.PubSubAuthorizationRules;

/**
 * Installs default broker credentials on first startup.
 *
 * <p>On every startup, checks if the default credential ({@code tbmq} username) already exists
 * in RocksDB. If not (first run), creates it with a bcrypt-hashed default password ({@code tbmq})
 * and allow-all ACL rules so the broker is immediately usable after {@code docker run}.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (after Spring's SmartLifecycle beans — including
 * RocksDB — have fully started) rather than {@code @PostConstruct} (which fires before
 * SmartLifecycle.start() and would see RocksDB as not running).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCredentialsInstaller {

    private static final String DEFAULT_USERNAME = "tbmq";
    private static final String DEFAULT_PASSWORD = "tbmq";

    private final LightweightCredentialService credentialService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void installDefaultCredentials() {
        LightweightCredential existing = credentialService.findByCredentialId(DEFAULT_USERNAME);
        if (existing != null) {
            log.debug("Default credentials already present: username={}", DEFAULT_USERNAME);
            return;
        }

        try {
            BasicMqttCredentials basicCreds = new BasicMqttCredentials(
                    passwordEncoder.encode(DEFAULT_PASSWORD),
                    PubSubAuthorizationRules.defaultInstance());

            String credentialValue = objectMapper.writeValueAsString(basicCreds);
            LightweightCredential credential = new LightweightCredential(DEFAULT_USERNAME, CredentialType.BASIC, credentialValue);
            credentialService.saveCredential(credential);
            log.info("Default credentials installed: username={}", DEFAULT_USERNAME);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to install default credentials", e);
        }
    }

}
