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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.StorageConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs prominent startup warnings for common misconfigurations in TBMQ Lightweight.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (after all SmartLifecycle components have started,
 * per D-10) at {@code @Order(2)} — after {@link DefaultCredentialsInstaller} ({@code @Order(1)}).
 *
 * <p>Warning conditions (D-11, D-12):
 * <ol>
 *   <li>TLS not configured — MQTT traffic is unencrypted</li>
 *   <li>RocksDB path is on an overlay filesystem — credentials will be lost on container removal</li>
 *   <li>Retained messages are stored in-memory only — always warned in R1</li>
 * </ol>
 *
 * <p>Warning output (D-09): A bordered multi-line banner logged at WARN level,
 * visible in {@code docker logs} output.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupWarningService {

    private final TlsConfiguration tlsConfig;
    private final StorageConfiguration storageConfig;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void checkStartupWarnings() {
        List<String> warnings = new ArrayList<>();

        // D-11 condition 1: TLS not configured
        if (!tlsConfig.isEnabled()) {
            warnings.add("TLS is not configured. MQTT traffic is unencrypted.");
            warnings.add("  Set TBMQ_TLS_ENABLED=true and mount certs to enable MQTTS on port 8883.");
        }

        // D-11 condition 2 / D-12: RocksDB path on overlay filesystem (Docker non-mounted)
        if (isRocksDbPathOverlay(storageConfig.getPath())) {
            warnings.add("RocksDB path '" + storageConfig.getPath() + "' is NOT volume-mounted.");
            warnings.add("  Credentials and ACLs will be LOST when the container is removed.");
            warnings.add("  Mount a volume: docker run -v /host/data:/data/rocksdb ...");
        }

        // R1 limitation: always log at INFO — this is not a misconfiguration
        log.info("NOTE: Retained messages are stored in-memory only. They will be lost on broker restart (R1 limitation).");

        // Only emit the WARNING banner for actual misconfigurations
        if (!warnings.isEmpty()) {
            String border = "=".repeat(70);
            log.warn("\n{}\n  TBMQ LIGHTWEIGHT — STARTUP WARNINGS\n{}\n  {}\n{}",
                    border, border,
                    String.join("\n  ", warnings),
                    border);
        }
    }

    /**
     * Checks whether the given data path resides on an overlay filesystem.
     *
     * <p>Reads {@code /proc/mounts} and finds the longest-matching mount point for
     * {@code dataPath}. If that mount's filesystem type is {@code overlay}, the path
     * is inside Docker's writable layer (not volume-mounted), and this method returns
     * {@code true}.
     *
     * <p>Returns {@code false} on non-Linux systems (where {@code /proc/mounts} does
     * not exist) and on any {@link IOException}.
     *
     * <p>Package-private for testability.
     *
     * @param dataPath the absolute filesystem path to check (e.g., {@code /data/rocksdb})
     * @return {@code true} if {@code dataPath} is on an overlay filesystem
     */
    boolean isRocksDbPathOverlay(String dataPath) {
        try {
            Path mountsFile = Path.of("/proc/mounts");
            if (!Files.exists(mountsFile)) {
                return false; // not Linux — skip check
            }
            String bestMountPoint = "/";
            String bestFsType = "unknown";
            for (String line : Files.readAllLines(mountsFile)) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                String mountPoint = parts[1];
                String fsType = parts[2];
                if (dataPath.startsWith(mountPoint) && mountPoint.length() > bestMountPoint.length()) {
                    bestMountPoint = mountPoint;
                    bestFsType = fsType;
                }
            }
            return "overlay".equals(bestFsType);
        } catch (IOException e) {
            log.debug("Could not read /proc/mounts for volume detection: {}", e.getMessage());
            return false;
        }
    }

}
