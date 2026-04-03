package org.thingsboard.mqtt.broker.lightweight.storage.rocksdb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.StorageConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * RocksDB embedded storage implementation with Spring SmartLifecycle ordering.
 *
 * <p>Lifecycle: Starts at {@link Integer#MIN_VALUE} phase (first to start, last to stop).
 * All services that depend on RocksDB must declare a higher SmartLifecycle phase.
 *
 * <p>Column families are opened in a single atomic {@code RocksDB.open()} call.
 * On first startup, writes {@code schema_version=1} to the METADATA column family
 * to support future R2 schema migration.
 *
 * <p>All RocksDB native resources are explicitly closed in reverse-open order:
 * handles first, then database, then options (finalizers removed in RocksDB v7+).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRocksDbStorage implements RocksDbStorage, SmartLifecycle {

    private static final String SCHEMA_VERSION_KEY = "schema_version";
    private static final String CURRENT_SCHEMA_VERSION = "1";

    private final StorageConfiguration config;
    private final MeterRegistry meterRegistry;

    private volatile boolean running = false;
    private RocksDB db;
    private DBOptions dbOptions;
    private final Map<RocksDbColumnFamily, ColumnFamilyHandle> cfHandleMap = new EnumMap<>(RocksDbColumnFamily.class);
    private final List<ColumnFamilyHandle> allHandles = new ArrayList<>();

    @Override
    public int getPhase() {
        // Integer.MIN_VALUE ensures RocksDB starts first and stops last
        // All dependent services must use a higher phase number
        return Integer.MIN_VALUE;
    }

    @Override
    public void start() {
        log.info("Starting RocksDB at path: {}", config.getPath());

        // MUST be called before any RocksDB operations (JNI library load)
        RocksDB.loadLibrary();

        try {
            // Ensure the data directory exists
            Files.createDirectories(Path.of(config.getPath()));

            // Build column family descriptors: default CF first, then all named CFs
            List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
            cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
            for (RocksDbColumnFamily cf : RocksDbColumnFamily.values()) {
                cfDescriptors.add(new ColumnFamilyDescriptor(cf.getName().getBytes(UTF_8), new ColumnFamilyOptions()));
            }

            // Configure DB options
            dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true);

            // Open ALL column families in a single call (anti-pattern: never open CFs separately)
            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, config.getPath(), cfDescriptors, cfHandles);

            // allHandles[0] = default CF handle (not mapped to enum)
            // allHandles[1..N] = named CF handles in enum declaration order
            allHandles.addAll(cfHandles);

            RocksDbColumnFamily[] cfValues = RocksDbColumnFamily.values();
            for (int i = 0; i < cfValues.length; i++) {
                // cfHandles[0] is default, named CFs start at index 1
                cfHandleMap.put(cfValues[i], cfHandles.get(i + 1));
            }

            // Mark as running before schema init so put/get guards pass
            running = true;

            // Initialize schema version on first startup
            String existingSchemaVersion = get(RocksDbColumnFamily.METADATA, SCHEMA_VERSION_KEY);
            if (existingSchemaVersion == null) {
                put(RocksDbColumnFamily.METADATA, SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION);
                log.info("Initialized RocksDB schema version to {}", CURRENT_SCHEMA_VERSION);
            } else {
                log.info("RocksDB schema version: {}", existingSchemaVersion);
            }
            log.info("RocksDB opened at {} with {} column families", config.getPath(), RocksDbColumnFamily.values().length);

        } catch (RocksDBException | IOException e) {
            throw new IllegalStateException("Failed to open RocksDB at path: " + config.getPath(), e);
        }
    }

    @Override
    public void stop() {
        log.info("Closing RocksDB...");

        // Close ColumnFamilyHandle objects FIRST (before db.close() — pitfall: handles must be closed before db)
        for (ColumnFamilyHandle handle : allHandles) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("Error closing RocksDB column family handle: {}", e.getMessage());
            }
        }
        allHandles.clear();
        cfHandleMap.clear();

        // Close database (flushes memtable to disk)
        if (db != null) {
            db.close();
            db = null;
        }

        // Close options
        if (dbOptions != null) {
            dbOptions.close();
            dbOptions = null;
        }

        running = false;
        log.info("RocksDB closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void put(RocksDbColumnFamily cf, String key, String value) {
        ColumnFamilyHandle handle = getHandle(cf);
        Timer timer = Timer.builder("rocksdb.write.latency")
                .tag("cf", cf.getName())
                .register(meterRegistry);
        timer.record(() -> {
            try {
                db.put(handle, key.getBytes(UTF_8), value.getBytes(UTF_8));
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB put failed for key: " + key + " in CF: " + cf.getName(), e);
            }
        });
    }

    @Override
    public String get(RocksDbColumnFamily cf, String key) {
        ColumnFamilyHandle handle = getHandle(cf);
        Timer timer = Timer.builder("rocksdb.read.latency")
                .tag("cf", cf.getName())
                .register(meterRegistry);
        return timer.record(() -> {
            try {
                byte[] value = db.get(handle, key.getBytes(UTF_8));
                return value == null ? null : new String(value, UTF_8);
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB get failed for key: " + key + " in CF: " + cf.getName(), e);
            }
        });
    }

    @Override
    public void delete(RocksDbColumnFamily cf, String key) {
        ColumnFamilyHandle handle = getHandle(cf);
        try {
            db.delete(handle, key.getBytes(UTF_8));
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB delete failed for key: " + key + " in CF: " + cf.getName(), e);
        }
    }

    private ColumnFamilyHandle getHandle(RocksDbColumnFamily cf) {
        if (!running) {
            throw new IllegalStateException("RocksDB is not running");
        }
        ColumnFamilyHandle handle = cfHandleMap.get(cf);
        if (handle == null) {
            throw new IllegalStateException("No handle found for column family: " + cf.getName());
        }
        return handle;
    }

}
