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
package org.thingsboard.mqtt.broker.lightweight.server.tls;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;
import org.thingsboard.mqtt.broker.lightweight.ssl.PemSslCredentials;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Factory for Netty {@link SslHandler} instances used in the TLS MQTT listener.
 *
 * <p>Builds the {@link SslContext} once at startup from the configured PEM certificate
 * and key files. The context is cached and used to create per-connection {@link SslHandler}
 * instances without rebuilding the context on each connection.
 *
 * <p>Supports three client auth modes (per {@code tbmq.tls.client-auth}):
 * <ul>
 *   <li>{@code NONE} — no client certificate required (TLS-only)</li>
 *   <li>{@code OPTIONAL} — client cert accepted but not required</li>
 *   <li>{@code REQUIRED} — mTLS: client cert required for TLS handshake</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MqttSslHandlerProvider {

    private final TlsConfiguration tlsConfig;

    private SslContext sslContext;

    @PostConstruct
    public void init() throws Exception {
        if (!tlsConfig.isEnabled()) {
            return;
        }

        log.info("Initializing SSL context from cert={}, key={}, clientAuth={}",
                tlsConfig.getCertPath(), tlsConfig.getKeyPath(), tlsConfig.getClientAuth());

        // Load server certificate and private key
        PemSslCredentials serverCreds = new PemSslCredentials();
        serverCreds.setCertFile(tlsConfig.getCertPath());
        serverCreds.setKeyFile(tlsConfig.getKeyPath());
        serverCreds.init(false);

        KeyManagerFactory kmf = serverCreds.createKeyManagerFactory();

        ClientAuth clientAuth = parseClientAuth(tlsConfig.getClientAuth());

        SslContextBuilder builder = SslContextBuilder.forServer(kmf)
                .protocols("TLSv1.2", "TLSv1.3")
                .clientAuth(clientAuth);

        // Load trust store for client CA verification when mTLS is enabled
        if (clientAuth != ClientAuth.NONE) {
            String trustCertPath = tlsConfig.getTrustCertPath();
            if (trustCertPath != null && !trustCertPath.isEmpty()) {
                PemSslCredentials trustCreds = new PemSslCredentials();
                trustCreds.setCertFile(trustCertPath);
                trustCreds.init(true);
                TrustManagerFactory tmf = trustCreds.createTrustManagerFactory();
                builder.trustManager(tmf);
                log.info("Client certificate verification enabled with CA from: {}", trustCertPath);
            } else {
                log.warn("Client auth is {} but no trust-cert-path configured; client certs will not be verified against a CA", clientAuth);
            }
        }

        this.sslContext = builder.build();
        log.info("SSL context initialized successfully (clientAuth={})", clientAuth);
    }

    /**
     * Creates a new {@link SslHandler} for the given channel.
     * Called once per incoming TLS connection.
     *
     * @param channel the newly accepted socket channel
     * @return a configured {@link SslHandler} ready to be added to the Netty pipeline
     */
    public SslHandler createSslHandler(SocketChannel channel) {
        if (sslContext == null) {
            throw new IllegalStateException("SslContext not initialized — TLS is not enabled");
        }
        return sslContext.newHandler(channel.alloc());
    }

    private static ClientAuth parseClientAuth(String clientAuth) {
        if (clientAuth == null) {
            return ClientAuth.NONE;
        }
        return switch (clientAuth.toUpperCase()) {
            case "REQUIRED" -> ClientAuth.REQUIRE;
            case "OPTIONAL" -> ClientAuth.OPTIONAL;
            default -> ClientAuth.NONE;
        };
    }

}
