package org.thingsboard.mqtt.broker.lightweight.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Netty TCP server bootstrap.
 *
 * <p>Uses port=0 for random port assignment to avoid conflicts in CI.
 * max-connections=3 for testing the connection limit enforcement.
 */
@SpringBootTest(properties = {
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=3",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-netty",
        "management.server.port=0"
})
@DirtiesContext
class NettyServerBootstrapTest {

    @Autowired
    private MqttTcpServerBootstrap bootstrap;

    @Autowired
    private ConnectionCountHandler connectionCountHandler;

    @Test
    void testGetPhase_returnsZero() {
        assertThat(bootstrap.getPhase()).isEqualTo(0);
    }

    @Test
    void testTcpConnect_succeeds() throws IOException {
        int port = bootstrap.getLocalPort();
        assertThat(port).isGreaterThan(0);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void testConnectionCount_incrementsAndDecrements() throws IOException, InterruptedException {
        int port = bootstrap.getLocalPort();

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);

        // Wait for channelActive to fire
        Thread.sleep(100);
        assertThat(connectionCountHandler.getConnectionCount()).isGreaterThan(0);

        socket.close();

        // Wait for channelInactive to fire
        Thread.sleep(200);
        assertThat(connectionCountHandler.getConnectionCount()).isEqualTo(0);
    }

    @Test
    void testConnectionLimit_rejectsExcessConnections() throws IOException, InterruptedException {
        int port = bootstrap.getLocalPort();
        Socket s1 = new Socket();
        Socket s2 = new Socket();
        Socket s3 = new Socket();
        Socket s4 = null;

        try {
            s1.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s2.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s3.connect(new InetSocketAddress("127.0.0.1", port), 1000);

            // Wait for all three to be counted
            Thread.sleep(100);
            assertThat(connectionCountHandler.getConnectionCount()).isEqualTo(3);

            // 4th connection — should be rejected (channel closed by server)
            s4 = new Socket();
            s4.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s4.setSoTimeout(1000);

            // The server closes the channel — read should return -1
            int result = s4.getInputStream().read();
            assertThat(result).isEqualTo(-1);
        } finally {
            s1.close();
            s2.close();
            s3.close();
            if (s4 != null) {
                s4.close();
            }
        }
    }

    @Test
    void testServerIsRunning_afterStartup() {
        assertThat(bootstrap.isRunning()).isTrue();
    }

}
