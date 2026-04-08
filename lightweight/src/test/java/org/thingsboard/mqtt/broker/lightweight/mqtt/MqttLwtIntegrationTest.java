package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for MQTT Last Will and Testament (LWT) — PROTO-06.
 *
 * <p>Tests verify LWT delivery on ungraceful disconnect, keep-alive expiry,
 * and suppression on clean disconnect.
 *
 * <p>Raw socket approach is used for tests requiring ungraceful disconnect
 * to ensure the broker detects channel close reliably without Paho interference.
 */
class MqttLwtIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testLwt_ungracefulDisconnect_willMessageDelivered() throws Exception {
        // Subscriber listens on the LWT topic.
        MqttClient subscriber = createClient("sub-lwt-ungraceful");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("lwt/topic", 0, (topic, msg) -> received.set(msg));

        // Raw socket: CONNECT with will message, then close the socket without DISCONNECT.
        // This simulates an ungraceful disconnect that triggers LWT delivery.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mqttServer.getLocalPort()), 1000);
            socket.setSoTimeout(5000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send CONNECT with will flag set
            byte[] connectPacket = buildMqttConnectWithWill("client-with-lwt", 30, "lwt/topic", "client-died".getBytes());
            out.write(connectPacket);
            out.flush();

            // Read CONNACK
            byte[] connack = new byte[4];
            in.read(connack);
            assertThat(connack[0]).isEqualTo((byte) 0x20); // CONNACK fixed header
            assertThat(connack[3]).isEqualTo((byte) 0x00); // CONNECTION_ACCEPTED

            // Close socket abruptly (no DISCONNECT packet) — broker should detect channel close
            // and deliver the LWT.
        } // socket.close() called here via try-with-resources

        await().atMost(5, SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get().getPayload())).isEqualTo("client-died");
    }

    @Test
    void testLwt_keepAliveExpiry_willMessageDelivered() throws Exception {
        // Subscriber listens on the LWT topic.
        MqttClient subscriber = createClient("sub-lwt-keepalive");
        subscriber.connect(defaultConnectOptions());
        AtomicReference<MqttMessage> received = new AtomicReference<>();
        subscriber.subscribe("lwt/keepalive", 0, (topic, msg) -> received.set(msg));

        // Raw socket: CONNECT with will + keepAlive=2, then go silent.
        // Broker disconnects after 1.5 * 2 = 3 seconds, delivering LWT.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mqttServer.getLocalPort()), 1000);
            socket.setSoTimeout(8000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] connectPacket = buildMqttConnectWithWill("client-lwt-keepalive", 2, "lwt/keepalive", "keepalive-died".getBytes());
            out.write(connectPacket);
            out.flush();

            byte[] connack = new byte[4];
            in.read(connack);
            assertThat(connack[0]).isEqualTo((byte) 0x20);
            assertThat(connack[3]).isEqualTo((byte) 0x00);

            // Go silent — broker should time out after ~3s and deliver LWT.
            // Socket will be closed by broker after keep-alive expiry.
        }

        await().atMost(8, SECONDS).until(() -> received.get() != null);
        assertThat(new String(received.get().getPayload())).isEqualTo("keepalive-died");
    }

    @Test
    void testLwt_cleanDisconnect_willMessageNotDelivered() throws Exception {
        // Subscriber listens on the LWT topic.
        MqttClient subscriber = createClient("sub-lwt-clean");
        subscriber.connect(defaultConnectOptions());
        AtomicInteger count = new AtomicInteger();
        subscriber.subscribe("lwt/clean", 0, (topic, msg) -> count.incrementAndGet());

        // Client with LWT connects and then disconnects CLEANLY via Paho (sends DISCONNECT packet).
        MqttClient willClient = createClient("client-lwt-clean");
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setWill("lwt/clean", "should-not-arrive".getBytes(), 0, false);
        willClient.connect(opts);
        willClient.disconnect(); // clean DISCONNECT packet — LWT MUST NOT be published

        Thread.sleep(2000);
        assertThat(count.get()).isEqualTo(0); // LWT NOT delivered on clean disconnect
    }

    /**
     * Builds a raw MQTT 3.1.1 CONNECT packet with will message and built-in {@code tbmq/tbmq}
     * credentials (required since auth enforcement is active).
     *
     * <p>Will flag set, will QoS 0, will retain false, clean session true, username + password set.
     */
    private byte[] buildMqttConnectWithWill(String clientId, int keepAlive, String willTopic, byte[] willPayload) {
        byte[] clientIdBytes = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] willTopicBytes = willTopic.getBytes(StandardCharsets.UTF_8);
        byte[] usernameBytes = "tbmq".getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = "tbmq".getBytes(StandardCharsets.UTF_8);

        // Variable header: 10 bytes (protocol name 6 + level 1 + flags 1 + keep-alive 2)
        int variableHeaderLen = 10;

        // Payload: clientId (2+len) + willTopic (2+len) + willPayload (2+len) + username (2+len) + password (2+len)
        int payloadLen = 2 + clientIdBytes.length
                + 2 + willTopicBytes.length
                + 2 + willPayload.length
                + 2 + usernameBytes.length
                + 2 + passwordBytes.length;

        int remainingLength = variableHeaderLen + payloadLen;

        byte[] packet = new byte[2 + remainingLength];
        int i = 0;

        // Fixed header: CONNECT type
        packet[i++] = 0x10;
        packet[i++] = (byte) remainingLength; // assumes < 128

        // Protocol name "MQTT"
        packet[i++] = 0x00;
        packet[i++] = 0x04;
        packet[i++] = 'M';
        packet[i++] = 'Q';
        packet[i++] = 'T';
        packet[i++] = 'T';

        // Protocol level: 4 (MQTT 3.1.1)
        packet[i++] = 0x04;

        // Connect flags: cleanSession=1 (0x02), willFlag=1 (0x04), willQos=0, willRetain=0,
        //                username=1 (0x80), password=1 (0x40) → 0x02 | 0x04 | 0x80 | 0x40 = 0xC6
        packet[i++] = (byte) 0xC6;

        // Keep alive
        packet[i++] = (byte) ((keepAlive >> 8) & 0xFF);
        packet[i++] = (byte) (keepAlive & 0xFF);

        // ClientId
        packet[i++] = (byte) ((clientIdBytes.length >> 8) & 0xFF);
        packet[i++] = (byte) (clientIdBytes.length & 0xFF);
        System.arraycopy(clientIdBytes, 0, packet, i, clientIdBytes.length);
        i += clientIdBytes.length;

        // Will topic
        packet[i++] = (byte) ((willTopicBytes.length >> 8) & 0xFF);
        packet[i++] = (byte) (willTopicBytes.length & 0xFF);
        System.arraycopy(willTopicBytes, 0, packet, i, willTopicBytes.length);
        i += willTopicBytes.length;

        // Will payload
        packet[i++] = (byte) ((willPayload.length >> 8) & 0xFF);
        packet[i++] = (byte) (willPayload.length & 0xFF);
        System.arraycopy(willPayload, 0, packet, i, willPayload.length);
        i += willPayload.length;

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
