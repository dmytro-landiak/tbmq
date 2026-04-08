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
package org.thingsboard.mqtt.broker.lightweight.ssl;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/**
 * Utility class for SSL/TLS certificate operations.
 *
 * <p>Copied and trimmed from TBMQ's {@code util.SslUtil} — stripped
 * fingerprint utilities to keep only CN extraction used in mTLS auth.
 */
public class SslUtil {

    private SslUtil() {
    }

    /**
     * Extracts the Common Name (CN) from an X.509 certificate's subject DN using BouncyCastle.
     *
     * <p>Used in mTLS authentication to identify the client certificate's CN,
     * which is then matched against the RocksDB SSL credential key.
     *
     * @param certificate the X.509 client certificate
     * @return the CN value from the subject DN
     * @throws CertificateEncodingException if the certificate cannot be encoded for parsing
     */
    public static String parseCommonName(X509Certificate certificate) throws CertificateEncodingException {
        X500Name x500name = new JcaX509CertificateHolder(certificate).getSubject();
        RDN cn = x500name.getRDNs(BCStyle.CN)[0];
        return IETFUtils.valueToString(cn.getFirst().getValue());
    }

}
