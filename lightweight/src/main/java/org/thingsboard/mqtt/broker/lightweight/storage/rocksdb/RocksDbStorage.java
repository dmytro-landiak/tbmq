package org.thingsboard.mqtt.broker.lightweight.storage.rocksdb;

/**
 * Interface for RocksDB embedded storage operations.
 *
 * <p>Provides key-value CRUD operations scoped to a specific {@link RocksDbColumnFamily}.
 * Keys are UTF-8 strings (typically client ID, username, or topic path).
 * Values are JSON-serialized strings — callers are responsible for Jackson serialization/deserialization.
 *
 * <p>All operations throw {@link IllegalStateException} if the storage is not running.
 * Use {@link #isRunning()} to check storage availability before operations.
 */
public interface RocksDbStorage {

    /**
     * Writes a key-value pair to the specified column family.
     *
     * @param cf    the column family to write to
     * @param key   the key (UTF-8 string)
     * @param value the value (JSON-serialized UTF-8 string)
     * @throws IllegalStateException if RocksDB is not running
     */
    void put(RocksDbColumnFamily cf, String key, String value);

    /**
     * Reads a value by key from the specified column family.
     *
     * @param cf  the column family to read from
     * @param key the key to look up
     * @return the value as a UTF-8 string, or {@code null} if the key does not exist
     * @throws IllegalStateException if RocksDB is not running
     */
    String get(RocksDbColumnFamily cf, String key);

    /**
     * Deletes a key-value pair from the specified column family.
     * No-op if the key does not exist.
     *
     * @param cf  the column family to delete from
     * @param key the key to delete
     * @throws IllegalStateException if RocksDB is not running
     */
    void delete(RocksDbColumnFamily cf, String key);

    /**
     * Returns {@code true} if the RocksDB storage is currently running and ready for operations.
     */
    boolean isRunning();

}
