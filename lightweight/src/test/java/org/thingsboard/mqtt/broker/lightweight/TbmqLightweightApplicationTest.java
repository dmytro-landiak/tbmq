package org.thingsboard.mqtt.broker.lightweight;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.thingsboard.mqtt.broker.lightweight.config.StorageConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-rocksdb-ctx"
})
public class TbmqLightweightApplicationTest {

    @Autowired
    private StorageConfiguration storageConfiguration;

    @Test
    void contextLoads() {
        // Spring context loads successfully - just the fact this test runs is the assertion
    }

    @Test
    void configBindingTest() {
        // Verify the default path is /data/rocksdb when no override is given
        // In this test we override via TestPropertySource so we just verify it binds
        assertThat(storageConfiguration.getPath()).isNotNull();
        assertThat(storageConfiguration.getPath()).isNotEmpty();
    }

}
