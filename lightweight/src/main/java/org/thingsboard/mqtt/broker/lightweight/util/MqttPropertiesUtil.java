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
package org.thingsboard.mqtt.broker.lightweight.util;

import io.netty.handler.codec.mqtt.MqttProperties;
import org.thingsboard.mqtt.broker.lightweight.common.BrokerConstants;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsg;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for extracting and constructing MQTT 5.0 properties.
 *
 * <p>All methods handle null and {@link MqttProperties#NO_PROPERTIES} gracefully,
 * returning appropriate defaults. This prevents NPEs from untrusted client input.
 *
 * <p>Design notes:
 * <ul>
 *   <li>All methods are static — no instance needed.</li>
 *   <li>Property extraction returns defaults when property is absent (never throws).</li>
 *   <li>Property addition mutates the provided {@code MqttProperties} object.</li>
 * </ul>
 */
public final class MqttPropertiesUtil {

    private MqttPropertiesUtil() {
    }

    // -------------------------------------------------------------------------
    // Session Expiry Interval (CONNECT / CONNACK / DISCONNECT)
    // -------------------------------------------------------------------------

    /**
     * Extracts the Session Expiry Interval from CONNECT properties.
     *
     * @return the interval in seconds, or {@code null} if absent
     */
    public static Integer getSessionExpiryIntervalFromConnect(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.SESSION_EXPIRY_INTERVAL_PROP_ID);
        return property != null ? property.value() : null;
    }

    /**
     * Adds the Session Expiry Interval property (four-byte integer) to {@code props}.
     */
    public static void addSessionExpiryIntervalToProps(MqttProperties props, int value) {
        props.add(new MqttProperties.IntegerProperty(BrokerConstants.SESSION_EXPIRY_INTERVAL_PROP_ID, value));
    }

    // -------------------------------------------------------------------------
    // Topic Alias Maximum (CONNACK)
    // -------------------------------------------------------------------------

    /**
     * Adds the Topic Alias Maximum property to {@code props}.
     */
    public static void addMaxTopicAliasToProps(MqttProperties props, int value) {
        props.add(new MqttProperties.IntegerProperty(BrokerConstants.TOPIC_ALIAS_MAX_PROP_ID, value));
    }

    /**
     * Extracts the Topic Alias Maximum from CONNECT properties.
     *
     * @return the maximum, or {@code 0} if absent (client does not support topic aliases)
     */
    public static int getTopicAliasMaxFromConnect(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.TOPIC_ALIAS_MAX_PROP_ID);
        return property != null ? property.value() : 0;
    }

    // -------------------------------------------------------------------------
    // Receive Maximum (CONNECT / CONNACK)
    // -------------------------------------------------------------------------

    /**
     * Adds the Receive Maximum property to {@code props}.
     */
    public static void addReceiveMaxToProps(MqttProperties props, int value) {
        props.add(new MqttProperties.IntegerProperty(BrokerConstants.RECEIVE_MAXIMUM_PROP_ID, value));
    }

    /**
     * Extracts the Receive Maximum from CONNECT properties.
     *
     * @return the receive maximum, or {@link BrokerConstants#DEFAULT_RECEIVE_MAXIMUM} if absent
     */
    public static int getReceiveMaxFromConnect(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.RECEIVE_MAXIMUM_PROP_ID);
        return property != null ? property.value() : BrokerConstants.DEFAULT_RECEIVE_MAXIMUM;
    }

    // -------------------------------------------------------------------------
    // Topic Alias (PUBLISH)
    // -------------------------------------------------------------------------

    /**
     * Extracts the Topic Alias from PUBLISH properties.
     *
     * @return the alias value, or {@code 0} if absent (no alias used)
     */
    public static int getTopicAlias(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.TOPIC_ALIAS_PROP_ID);
        return property != null ? property.value() : 0;
    }

    /**
     * Adds the Topic Alias property to {@code props} (for outbound PUBLISH).
     */
    public static void addTopicAliasToProps(MqttProperties props, int topicAlias) {
        props.add(new MqttProperties.IntegerProperty(BrokerConstants.TOPIC_ALIAS_PROP_ID, topicAlias));
    }

    // -------------------------------------------------------------------------
    // Subscription Identifier (SUBSCRIBE / PUBLISH)
    // -------------------------------------------------------------------------

    /**
     * Extracts the Subscription Identifier from SUBSCRIBE properties.
     *
     * @return the identifier, or {@code 0} if absent
     */
    public static int getSubscriptionId(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.SUBSCRIPTION_IDENTIFIER_PROP_ID);
        return property != null ? property.value() : 0;
    }

    /**
     * Adds a Subscription Identifier property to outbound PUBLISH properties.
     * Only adds if {@code subscriptionId > 0}.
     */
    public static void addSubscriptionIdToProps(MqttProperties props, int subscriptionId) {
        if (subscriptionId > 0) {
            props.add(new MqttProperties.IntegerProperty(BrokerConstants.SUBSCRIPTION_IDENTIFIER_PROP_ID, subscriptionId));
        }
    }

    // -------------------------------------------------------------------------
    // Message Expiry Interval (PUBLISH)
    // -------------------------------------------------------------------------

    /**
     * Extracts the Message Expiry Interval from PUBLISH properties.
     *
     * @return the interval in seconds, or {@code null} if absent (message does not expire)
     */
    public static Integer getPubExpiryInterval(MqttProperties props) {
        MqttProperties.IntegerProperty property = getIntegerProperty(props, BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID);
        return property != null ? property.value() : null;
    }

    /**
     * Adds the Message Expiry Interval property to {@code props}.
     */
    public static void addPubExpiryIntervalToProps(MqttProperties props, int remainingSeconds) {
        props.add(new MqttProperties.IntegerProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID, remainingSeconds));
    }

    /**
     * Checks whether a retained message has expired.
     *
     * @param createdTime  the epoch millis when the message was stored
     * @param properties   the message's MQTT properties
     * @return {@code true} if the message has an expiry interval that has passed
     */
    public static boolean isRetainedMsgExpired(long createdTime, MqttProperties properties) {
        Integer expiryInterval = getPubExpiryInterval(properties);
        if (expiryInterval == null || expiryInterval <= 0) {
            return false;
        }
        return createdTime + TimeUnit.SECONDS.toMillis(expiryInterval) < System.currentTimeMillis();
    }

    /**
     * Convenience overload that checks expiry using the {@link RetainedMsg}'s stored fields.
     */
    public static boolean isRetainedMsgExpired(RetainedMsg retainedMsg) {
        return isRetainedMsgExpired(retainedMsg.getCreatedTime(), retainedMsg.getProperties());
    }

    /**
     * Computes the remaining expiry interval in seconds for message forwarding.
     *
     * <p>Per MQTT 5.0 spec [MQTT-3.3.2-6]: when forwarding a stored message, the broker
     * MUST subtract the time it has been stored from the original expiry interval.
     *
     * @param createdTime           epoch millis when the message was received
     * @param originalExpirySeconds the original expiry interval in seconds
     * @return remaining seconds, or {@code 0} if already expired
     */
    public static int getRemainingExpiryInterval(long createdTime, int originalExpirySeconds) {
        long elapsedMs = System.currentTimeMillis() - createdTime;
        long remainingMs = TimeUnit.SECONDS.toMillis(originalExpirySeconds) - elapsedMs;
        if (remainingMs <= 0) {
            return 0;
        }
        return Math.toIntExact(TimeUnit.MILLISECONDS.toSeconds(remainingMs));
    }

    // -------------------------------------------------------------------------
    // User Properties (PUBLISH)
    // -------------------------------------------------------------------------

    /**
     * Extracts User Properties from the given MQTT properties.
     *
     * @return list of {@link MqttProperties.StringPair} entries, or empty list if absent
     */
    public static List<MqttProperties.StringPair> getUserProperties(MqttProperties props) {
        if (props == null || props == MqttProperties.NO_PROPERTIES) {
            return Collections.emptyList();
        }
        MqttProperties.UserProperties userProps =
                (MqttProperties.UserProperties) props.getProperty(BrokerConstants.USER_PROPERTY_PROP_ID);
        if (userProps == null) {
            return Collections.emptyList();
        }
        return userProps.value();
    }

    /**
     * Copies all entries from {@code userProps} into {@code props} as a single User Properties entry.
     */
    public static void addUserPropertiesToProps(MqttProperties props, List<MqttProperties.StringPair> userProps) {
        if (userProps == null || userProps.isEmpty()) {
            return;
        }
        MqttProperties.UserProperties up = new MqttProperties.UserProperties();
        for (MqttProperties.StringPair pair : userProps) {
            up.add(pair);
        }
        props.add(up);
    }

    // -------------------------------------------------------------------------
    // Copy publish properties for outbound delivery
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link MqttProperties} object copying all properties relevant
     * for outbound PUBLISH delivery (user props, payload format indicator, content type,
     * response topic, correlation data, and message expiry interval).
     *
     * <p>The message expiry interval is copied as-is from the inbound PUBLISH.  When
     * forwarding a stored message to a subscriber the caller is responsible for
     * recomputing the remaining interval via
     * {@link #getRemainingExpiryInterval(long, int)}.  Storing the original interval
     * here is required so that the retained-message expiry check
     * ({@link #isRetainedMsgExpired}) can compare it against {@code createdTime}.
     *
     * @param source the original inbound PUBLISH properties
     * @return a new MqttProperties with the copied properties (never null)
     */
    public static MqttProperties copyPublishPropertiesToDeliver(MqttProperties source) {
        MqttProperties dest = new MqttProperties();
        if (source == null || source == MqttProperties.NO_PROPERTIES) {
            return dest;
        }

        // Message expiry interval — required for retained message expiry checks
        MqttProperties.MqttProperty expiryInterval = source.getProperty(BrokerConstants.PUB_EXPIRY_INTERVAL_PROP_ID);
        if (expiryInterval != null) {
            dest.add(expiryInterval);
        }

        // User properties
        MqttProperties.MqttProperty userProp = source.getProperty(BrokerConstants.USER_PROPERTY_PROP_ID);
        if (userProp != null) {
            dest.add(userProp);
        }

        // Payload format indicator
        MqttProperties.MqttProperty payloadFormat = source.getProperty(BrokerConstants.PAYLOAD_FORMAT_INDICATOR_PROP_ID);
        if (payloadFormat != null) {
            dest.add(payloadFormat);
        }

        // Content type
        MqttProperties.MqttProperty contentType = source.getProperty(BrokerConstants.CONTENT_TYPE_PROP_ID);
        if (contentType != null) {
            dest.add(contentType);
        }

        // Response topic
        MqttProperties.MqttProperty responseTopic = source.getProperty(BrokerConstants.RESPONSE_TOPIC_PROP_ID);
        if (responseTopic != null) {
            dest.add(responseTopic);
        }

        // Correlation data
        MqttProperties.MqttProperty correlationData = source.getProperty(BrokerConstants.CORRELATION_DATA_PROP_ID);
        if (correlationData != null) {
            dest.add(correlationData);
        }

        return dest;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static MqttProperties.IntegerProperty getIntegerProperty(MqttProperties properties, int propertyId) {
        if (properties == null || properties == MqttProperties.NO_PROPERTIES) {
            return null;
        }
        MqttProperties.MqttProperty property = properties.getProperty(propertyId);
        return property instanceof MqttProperties.IntegerProperty ? (MqttProperties.IntegerProperty) property : null;
    }

}
