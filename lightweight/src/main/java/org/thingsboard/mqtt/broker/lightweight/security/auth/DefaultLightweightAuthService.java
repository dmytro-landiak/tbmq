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
package org.thingsboard.mqtt.broker.lightweight.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.security.acl.AuthorizationRuleService;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * Unified authentication service supporting basic and SSL credential types.
 *
 * <p>Authentication priority:
 * <ol>
 *   <li>SSL/X.509 (if sslHandler present and peer certificate available)</li>
 *   <li>Basic username/password (if username non-empty)</li>
 *   <li>Anonymous (if anonymousEnabled=true and no credentials provided)</li>
 *   <li>Reject (if anonymousEnabled=false and no credentials provided)</li>
 * </ol>
 *
 * <p>Adapted from TBMQ's {@code BasicMqttClientAuthProvider} and {@code SslMqttClientAuthProvider},
 * simplified to a single unified service without the pluggable provider chain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLightweightAuthService implements LightweightAuthService {

    private final LightweightCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthorizationRuleService authorizationRuleService;

    @Value("${tbmq.security.anonymous-enabled:false}")
    private boolean anonymousEnabled;

    @Override
    public AuthResult authenticate(String username, String password, SslHandler sslHandler) {
        // 1. Try SSL/X.509 authentication first if TLS connection
        if (sslHandler != null) {
            AuthResult sslResult = trySSLAuth(sslHandler);
            if (sslResult != null) {
                return sslResult;
            }
            // SSL handler present but no peer cert — fall through to basic auth
        }

        // 2. Try basic username/password authentication
        if (username != null && !username.isEmpty()) {
            return tryBasicAuth(username, password);
        }

        // 3. No credentials provided — check anonymous setting
        if (anonymousEnabled) {
            log.debug("Anonymous connection allowed");
            return AuthResult.success(Collections.emptyList());
        } else {
            return AuthResult.failure("Anonymous connections not allowed");
        }
    }

    private AuthResult trySSLAuth(SslHandler sslHandler) {
        try {
            SSLSession session = sslHandler.engine().getSession();
            Certificate[] peerCerts = session.getPeerCertificates();
            if (peerCerts == null || peerCerts.length == 0) {
                return null;
            }
            X509Certificate peerCert = (X509Certificate) peerCerts[0];
            String cn = extractCN(peerCert);
            if (cn == null) {
                log.warn("Could not extract CN from peer certificate");
                return AuthResult.failure("Could not extract CN from client certificate");
            }

            LightweightCredential credential = credentialService.findByCredentialId(cn);
            if (credential == null || credential.getType() != CredentialType.SSL) {
                log.warn("No SSL credential found for CN: {}", cn);
                return AuthResult.failure("No credentials found for certificate CN: " + cn);
            }

            SslMqttCredentials sslCreds = objectMapper.readValue(credential.getCredentialValue(), SslMqttCredentials.class);
            List<AuthRulePatterns> patterns = authorizationRuleService.parseSslAuthorizationRule(sslCreds, cn);
            if (patterns.isEmpty()) {
                log.warn("No ACL rules matched for CN: {}", cn);
                return AuthResult.failure("No authorization rules matched for certificate CN: " + cn);
            }

            log.debug("SSL authentication succeeded for CN: {}", cn);
            return AuthResult.success(patterns);
        } catch (SSLPeerUnverifiedException e) {
            // No peer certificate — mTLS not required, fall through to basic auth
            return null;
        } catch (Exception e) {
            log.warn("SSL authentication error", e);
            return AuthResult.failure("SSL authentication error: " + e.getMessage());
        }
    }

    private AuthResult tryBasicAuth(String username, String password) {
        LightweightCredential credential = credentialService.findByCredentialId(username);
        if (credential == null || credential.getType() != CredentialType.BASIC) {
            log.warn("No basic credentials found for username: {}", username);
            return AuthResult.failure("Bad username or password");
        }

        try {
            BasicMqttCredentials basicCreds = objectMapper.readValue(credential.getCredentialValue(), BasicMqttCredentials.class);
            if (basicCreds.getPassword() != null) {
                if (password == null || !passwordEncoder.matches(password, basicCreds.getPassword())) {
                    log.warn("Password mismatch for username: {}", username);
                    return AuthResult.failure("Bad username or password");
                }
            }

            List<AuthRulePatterns> patterns = authorizationRuleService.parseAuthorizationRule(basicCreds);
            log.debug("Basic authentication succeeded for username: {}", username);
            return AuthResult.success(patterns);
        } catch (Exception e) {
            log.error("Failed to process basic credentials for username: {}", username, e);
            return AuthResult.failure("Bad username or password");
        }
    }

    /**
     * Extracts the Common Name (CN) from an X.509 certificate's subject distinguished name.
     */
    private String extractCN(X509Certificate cert) {
        String subjectDN = cert.getSubjectX500Principal().getName();
        for (String part : subjectDN.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

}
