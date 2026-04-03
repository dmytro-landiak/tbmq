package org.thingsboard.mqtt.broker.lightweight.storage.rocksdb;

/**
 * Enumeration of RocksDB column families used by TBMQ Lightweight.
 *
 * <p>Each enum value corresponds to a named column family in the RocksDB database.
 * All column families are opened in a single {@code RocksDB.open()} call for atomicity.
 *
 * <p>Column family layout:
 * <ul>
 *   <li>{@code CREDENTIALS} - MQTT client credentials (username/password, X.509)</li>
 *   <li>{@code ACL_RULES} - Topic-level ACL authorization rules</li>
 *   <li>{@code RETAINED_MESSAGES} - Retained MQTT messages indexed by topic</li>
 *   <li>{@code METADATA} - Broker metadata (e.g., schema_version for migrations)</li>
 * </ul>
 */
public enum RocksDbColumnFamily {

    CREDENTIALS("credentials"),
    ACL_RULES("acl_rules"),
    RETAINED_MESSAGES("retained_messages"),
    METADATA("metadata");

    private final String name;

    RocksDbColumnFamily(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
