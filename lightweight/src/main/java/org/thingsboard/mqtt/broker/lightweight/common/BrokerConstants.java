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
package org.thingsboard.mqtt.broker.lightweight.common;

public class BrokerConstants {

    public static final String NULL_CHAR_STR = "\u0000";
    public static final char TOPIC_DELIMITER = '/';
    public static final String TOPIC_DELIMITER_STR = "/";
    public static final String MULTI_LEVEL_WILDCARD = "#";
    public static final String SINGLE_LEVEL_WILDCARD = "+";

    // MQTT 5.0 Property IDs (from MQTT 5.0 spec section 2.2.2.2)
    public static final int PAYLOAD_FORMAT_INDICATOR_PROP_ID = 1;
    public static final int PUB_EXPIRY_INTERVAL_PROP_ID = 2;
    public static final int CONTENT_TYPE_PROP_ID = 3;
    public static final int RESPONSE_TOPIC_PROP_ID = 8;
    public static final int CORRELATION_DATA_PROP_ID = 9;
    public static final int SUBSCRIPTION_IDENTIFIER_PROP_ID = 11;
    public static final int SESSION_EXPIRY_INTERVAL_PROP_ID = 17;
    public static final int ASSIGNED_CLIENT_IDENTIFIER_PROP_ID = 18;
    public static final int RECEIVE_MAXIMUM_PROP_ID = 33;
    public static final int TOPIC_ALIAS_MAX_PROP_ID = 34;
    public static final int TOPIC_ALIAS_PROP_ID = 35;
    public static final int USER_PROPERTY_PROP_ID = 38;

    // MQTT 5.0 defaults
    public static final int DEFAULT_RECEIVE_MAXIMUM = 65535;

    // Shared subscription constants
    public static final String SHARED_SUBSCRIPTION_PREFIX = "$share/";
    public static final int SHARE_NAME_IDX = 7; // "$share/".length()

    private BrokerConstants() {
    }
}
