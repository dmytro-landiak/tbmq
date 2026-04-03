package org.thingsboard.mqtt.broker.lightweight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for RocksDB embedded storage.
 * Binds to {@code tbmq.storage.rocksdb.*} YAML properties.
 * Environment variable override: {@code TBMQ_STORAGE_ROCKSDB_PATH} (Spring relaxed binding).
 */
@Configuration
@ConfigurationProperties(prefix = "tbmq.storage.rocksdb")
@Data
public class StorageConfiguration {

    /**
     * Filesystem path where RocksDB stores its data files.
     * Default: {@code /data/rocksdb} (volume-mountable in Docker).
     * Override via {@code TBMQ_ROCKSDB_PATH} environment variable.
     */
    private String path = "/data/rocksdb";

}
