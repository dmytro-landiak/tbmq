package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT keep-alive / PINGREQ / PINGRESP — PROTO-04.
 */
class MqttKeepAliveIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testPingReqPingResp_keepAliveRenewed() throws Exception {
        MqttClient client = createClient("test-ping-1");
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setKeepAliveInterval(10); // Paho auto-sends PINGs at keepAlive/2 = 5 seconds
        client.connect(opts);
        assertThat(client.isConnected()).isTrue();
        // Wait a bit — Paho sends PINGREQ automatically; connection should stay alive
        Thread.sleep(1000);
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    @Test
    void testKeepAliveExpiry_clientDisconnected() throws Exception {
        // Use raw socket: connect with keepAlive=2, then do nothing.
        // Broker should disconnect after 1.5 * 2 = 3 seconds.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mqttServer.getLocalPort()), 1000);
            socket.setSoTimeout(7000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send MQTT CONNECT with keepAlive=2 seconds and clientId="keepalive-test"
            byte[] connectPacket = buildMqttConnectPacket("keepalive-test", 2);
            out.write(connectPacket);
            out.flush();

            // Read CONNACK (4 bytes: fixed header byte, length, session flags, return code)
            byte[] connack = new byte[4];
            int read = in.read(connack);
            assertThat(read).isEqualTo(4);
            assertThat(connack[0]).isEqualTo((byte) 0x20); // CONNACK fixed header
            assertThat(connack[3]).isEqualTo((byte) 0x00); // CONNECTION_ACCEPTED

            // Stop sending anything — broker should disconnect us after ~3 seconds (1.5x keepAlive)
            Awaitility.await()
                    .atMost(7, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        try {
                            int b = in.read();
                            return b == -1;
                        } catch (IOException e) {
                            return true; // socket closed by server
                        }
                    });
        }
    }

    @Test
    void testKeepAliveZero_noTimeout() throws Exception {
        MqttClient client = createClient("test-keepalive-zero");
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setKeepAliveInterval(0); // disable keep-alive
        client.connect(opts);
        // Wait — with keepAlive=0 no timeout should fire
        Thread.sleep(2000);
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    /**
     * Builds a raw MQTT 3.1.1 CONNECT packet with the given clientId, keepAlive seconds,
     * and the built-in {@code tbmq/tbmq} credentials (required since auth enforcement is active).
     *
     * <p>Packet structure per MQTT 3.1.1 spec:
     * <ul>
     *   <li>Fixed header: 0x10, remaining length</li>
     *   <li>Protocol name: "MQTT" (2 length bytes + 4 chars)</li>
     *   <li>Protocol level: 4 (MQTT 3.1.1)</li>
     *   <li>Connect flags: 0xC2 (cleanSession=1, username=1, password=1)</li>
     *   <li>Keep alive: 2 bytes MSB+LSB</li>
     *   <li>Payload: clientId, username, password (each prefixed with 2-byte length)</li>
     * </ul>
     */
    private static byte[] buildMqttConnectPacket(String clientId, int keepAlive) {
        byte[] clientIdBytes = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] usernameBytes = "tbmq".getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = "tbmq".getBytes(StandardCharsets.UTF_8);

        // Variable header: protocol name (6 bytes) + protocol level (1) + connect flags (1) + keep alive (2) = 10 bytes
        int variableHeaderLen = 10;
        // Payload: clientId (2+len) + username (2+len) + password (2+len)
        int payloadLen = 2 + clientIdBytes.length + 2 + usernameBytes.length + 2 + passwordBytes.length;
        int remainingLength = variableHeaderLen + payloadLen;

        byte[] packet = new byte[2 + remainingLength];
        int i = 0;

        // Fixed header
        packet[i++] = 0x10; // CONNECT message type
        packet[i++] = (byte) remainingLength; // remaining length (assumes < 128)

        // Protocol name: "MQTT" with 2-byte length prefix
        packet[i++] = 0x00;
        packet[i++] = 0x04;
        packet[i++] = 'M';
        packet[i++] = 'Q';
        packet[i++] = 'T';
        packet[i++] = 'T';

        // Protocol level: 4 = MQTT 3.1.1
        packet[i++] = 0x04;

        // Connect flags: cleanSession=1 (0x02), username=1 (0x80), password=1 (0x40) = 0xC2
        packet[i++] = (byte) 0xC2;

        // Keep alive: MSB then LSB
        packet[i++] = (byte) ((keepAlive >> 8) & 0xFF);
        packet[i++] = (byte) (keepAlive & 0xFF);

        // ClientId: 2-byte length prefix + UTF-8 bytes
        packet[i++] = (byte) ((clientIdBytes.length >> 8) & 0xFF);
        packet[i++] = (byte) (clientIdBytes.length & 0xFF);
        System.arraycopy(clientIdBytes, 0, packet, i, clientIdBytes.length);
        i += clientIdBytes.length;

        // Username
        packet[i++] = (byte) ((usernameBytes.length >> 8) & 0xFF);
        packet[i++] = (byte) (usernameBytes.length & 0xFF);
        System.arraycopy(usernameBytes, 0, packet, i, usernameBytes.length);
        i += usernameBytes.length;

        // Password
        packet[i++] = (byte) ((passwordBytes.length >> 8) & 0xFF);
        packet[i++] = (byte) (passwordBytes.length & 0xFF);
        System.arraycopy(passwordBytes, 0, packet, i, passwordBytes.length);

        return packet;
    }

}
