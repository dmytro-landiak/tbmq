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

import io.netty.handler.ssl.SslHandler;

/**
 * Unified authentication service for MQTT clients.
 *
 * <p>Supports two authentication paths:
 * <ol>
 *   <li>SSL/X.509 certificate authentication (when {@code sslHandler} is non-null)</li>
 *   <li>Basic username/password authentication (when username is provided)</li>
 * </ol>
 *
 * <p>If neither credential is provided and anonymous access is enabled
 * ({@code tbmq.security.anonymous-enabled=true}), returns a success result with empty
 * ACL patterns (bypasses all ACL checks).
 *
 * <p>Called from {@code ClientActor.processConnect()} during MQTT CONNECT handling.
 */
public interface LightweightAuthService {

    /**
     * Authenticates an MQTT client.
     *
     * @param username   the MQTT username from the CONNECT packet (may be null)
     * @param password   the MQTT password from the CONNECT packet (may be null)
     * @param sslHandler the Netty SSL handler if the connection is TLS, or null for plain TCP
     * @return {@link AuthResult#success(java.util.List)} on valid credentials, or
     *         {@link AuthResult#failure(String)} with a reason message on invalid credentials
     */
    AuthResult authenticate(String username, String password, SslHandler sslHandler);

}
