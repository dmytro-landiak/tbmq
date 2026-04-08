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
package org.thingsboard.mqtt.broker.lightweight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collections;

/**
 * Spring Security configuration for TBMQ Lightweight.
 *
 * <p>Security policy for the HTTP/Actuator interface:
 * <ul>
 *   <li>Actuator endpoints ({@code /actuator/**}) are publicly accessible without authentication</li>
 *   <li>All other HTTP requests are permitted (no REST API auth in R1 — MQTT is the only protocol
 *       that requires credentials)</li>
 *   <li>CSRF is disabled (stateless MQTT broker, no browser forms)</li>
 * </ul>
 *
 * <p>MQTT authentication is handled separately in {@code DefaultLightweightAuthService},
 * not via Spring Security filters.
 *
 * <p>The empty {@link InMemoryUserDetailsManager} prevents Spring Boot from logging a random
 * password on startup (the default behavior when no {@link UserDetailsService} bean is defined).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Empty in-memory user store — suppresses Spring Boot's random password log message
     * and prevents Spring Security from auto-configuring HTTP Basic auth for REST endpoints.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(Collections.emptyList());
    }

}
