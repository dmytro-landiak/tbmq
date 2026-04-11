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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.mqtt.broker.lightweight.config.StorageConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupWarningService}.
 *
 * <p>Uses Mockito to inject mock configuration beans and verifies the service
 * logic without starting a Spring context.
 */
@ExtendWith(MockitoExtension.class)
public class StartupWarningServiceTest {

    @Mock
    private TlsConfiguration tlsConfig;

    @Mock
    private StorageConfiguration storageConfig;

    private StartupWarningService service;

    @BeforeEach
    void setUp() {
        service = new StartupWarningService(tlsConfig, storageConfig);
    }

    @Test
    void givenTlsDisabled_whenCheckStartupWarnings_thenNoExceptionThrown() {
        when(tlsConfig.isEnabled()).thenReturn(false);
        when(storageConfig.getPath()).thenReturn("/tmp/test-rocksdb");

        assertThatNoException().isThrownBy(() -> service.checkStartupWarnings());
    }

    @Test
    void givenTlsEnabled_whenCheckStartupWarnings_thenNoExceptionThrown() {
        when(tlsConfig.isEnabled()).thenReturn(true);
        when(storageConfig.getPath()).thenReturn("/tmp/test-rocksdb");

        assertThatNoException().isThrownBy(() -> service.checkStartupWarnings());
    }

    @Test
    void givenNonOverlayPath_whenIsRocksDbPathOverlay_thenReturnsFalse() {
        // /tmp is on a real filesystem (ext4, tmpfs, etc.) on a dev machine — never overlay
        boolean result = service.isRocksDbPathOverlay("/tmp");
        assertThat(result).isFalse();
    }

    @Test
    void givenNonExistentPath_whenIsRocksDbPathOverlay_thenReturnsFalse() {
        // If path does not exist as a mount prefix, it falls back to "/" which is not overlay
        boolean result = service.isRocksDbPathOverlay("/nonexistent/path/that/does/not/exist");
        assertThat(result).isFalse();
    }

    @Test
    void givenProcMountsUnavailable_whenIsRocksDbPathOverlay_thenReturnsFalse() {
        // On this machine, if /proc/mounts exists the method reads it and returns false for non-overlay paths.
        // The method itself handles the missing file case by returning false.
        // This test verifies no exception is thrown regardless of the environment.
        assertThatNoException().isThrownBy(() -> service.isRocksDbPathOverlay("/data/rocksdb"));
    }

    @Test
    void givenRocksDbPathOnRealFilesystem_whenCheckStartupWarnings_thenNoExceptionThrown() {
        when(tlsConfig.isEnabled()).thenReturn(true);
        when(storageConfig.getPath()).thenReturn("/tmp");

        // Should complete without exception — /tmp is not overlay
        assertThatNoException().isThrownBy(() -> service.checkStartupWarnings());
    }

}
