package org.thingsboard.mqtt.broker.lightweight.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thingsboard.mqtt.broker.lightweight.config.StorageConfiguration;
import org.thingsboard.mqtt.broker.lightweight.storage.rocksdb.DefaultRocksDbStorage;
import org.thingsboard.mqtt.broker.lightweight.storage.rocksdb.RocksDbColumnFamily;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DefaultRocksDbStorage}.
 * Uses a temporary directory to avoid touching production data paths.
 */
class RocksDbStorageTest {

    @TempDir
    File tempDir;

    private DefaultRocksDbStorage storage;

    @BeforeEach
    void setUp() {
        storage = createStorage(tempDir.getAbsolutePath());
        storage.start();
    }

    @AfterEach
    void tearDown() {
        if (storage != null && storage.isRunning()) {
            storage.stop();
        }
    }

    /**
     * Test 1: Basic put/get on CREDENTIALS column family.
     */
    @Test
    void putAndGetReturnsValue() {
        storage.put(RocksDbColumnFamily.CREDENTIALS, "testKey", "testValue");

        String result = storage.get(RocksDbColumnFamily.CREDENTIALS, "testKey");

        assertThat(result).isEqualTo("testValue");
    }

    /**
     * Test 2: Delete removes entry from CREDENTIALS column family.
     */
    @Test
    void putThenDeleteReturnsNull() {
        storage.put(RocksDbColumnFamily.CREDENTIALS, "testKey", "testValue");
        storage.delete(RocksDbColumnFamily.CREDENTIALS, "testKey");

        String result = storage.get(RocksDbColumnFamily.CREDENTIALS, "testKey");

        assertThat(result).isNull();
    }

    /**
     * Test 3: Data persists across stop/start cycles (RocksDB persistence).
     */
    @Test
    void dataPersistedAcrossRestarts() {
        storage.put(RocksDbColumnFamily.METADATA, "persistKey", "persistValue");
        storage.stop();

        // Restart with the same path
        storage = createStorage(tempDir.getAbsolutePath());
        storage.start();

        String result = storage.get(RocksDbColumnFamily.METADATA, "persistKey");
        assertThat(result).isEqualTo("persistValue");
    }

    /**
     * Test 4: schema_version=1 is written to METADATA CF on first startup.
     */
    @Test
    void schemaVersionInitializedOnFirstStartup() {
        String schemaVersion = storage.get(RocksDbColumnFamily.METADATA, "schema_version");

        assertThat(schemaVersion).isEqualTo("1");
    }

    /**
     * Test 5: All four column families are accessible — put/get works on each.
     */
    @Test
    void allColumnFamiliesAccessible() {
        storage.put(RocksDbColumnFamily.CREDENTIALS, "credKey", "credValue");
        storage.put(RocksDbColumnFamily.ACL_RULES, "aclKey", "aclValue");
        storage.put(RocksDbColumnFamily.RETAINED_MESSAGES, "retainKey", "retainValue");
        storage.put(RocksDbColumnFamily.METADATA, "metaKey", "metaValue");

        assertThat(storage.get(RocksDbColumnFamily.CREDENTIALS, "credKey")).isEqualTo("credValue");
        assertThat(storage.get(RocksDbColumnFamily.ACL_RULES, "aclKey")).isEqualTo("aclValue");
        assertThat(storage.get(RocksDbColumnFamily.RETAINED_MESSAGES, "retainKey")).isEqualTo("retainValue");
        assertThat(storage.get(RocksDbColumnFamily.METADATA, "metaKey")).isEqualTo("metaValue");
    }

    /**
     * Test 6: SmartLifecycle getPhase() returns Integer.MIN_VALUE (starts first, stops last).
     */
    @Test
    void lifecyclePhaseIsMinValue() {
        assertThat(storage.getPhase()).isEqualTo(Integer.MIN_VALUE);
    }

    private DefaultRocksDbStorage createStorage(String path) {
        StorageConfiguration config = new StorageConfiguration();
        config.setPath(path);
        return new DefaultRocksDbStorage(config, new SimpleMeterRegistry());
    }

}
