/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2010 Sun Microsystems, Inc.
 * Portions Copyright 2016 ForgeRock AS.
 * Portions Copyright 2021 Wren Security.
 */
package org.forgerock.opendj.ldap;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * An SSL context builder provides an interface for incrementally constructing
 * {@link SSLContext} instances for use when securing connections with SSL or
 * the StartTLS extended operation. The {@link #getSSLContext()} should be
 * called in order to obtain the {@code SSLContext}.
 * <p>
 * For example, use the SSL context builder when setting up LDAP options needed
 * to use StartTLS. {@link org.forgerock.opendj.ldap.TrustManagers
 * TrustManagers} has methods you can use to set the trust manager for the SSL
 * context builder.
 *
 * <pre>
 * LDAPOptions options = new LDAPOptions();
 * SSLContext sslContext =
 *         new SSLContextBuilder().setTrustManager(...).getSSLContext();
 * options.setSSLContext(sslContext);
 * options.setUseStartTLS(true);
 *
 * String host = ...;
 * int port = ...;
 * LDAPConnectionFactory factory = new LDAPConnectionFactory(host, port, options);
 * Connection connection = factory.getConnection();
 * // Connection uses StartTLS...
 * </pre>
 */
public final class SSLContextBuilder {
    /** SSL protocol: supports some version of SSL; may support other versions. */
    public static final String PROTOCOL_SSL = "SSL";
    /** SSL protocol: supports SSL version 2 or higher; may support other versions. */
    public static final String PROTOCOL_SSL2 = "SSLv2";
    /** SSL protocol: supports SSL version 3; may support other versions. */
    public static final String PROTOCOL_SSL3 = "SSLv3";
    /** SSL protocol: supports some version of TLS; may support other versions. */
    public static final String PROTOCOL_TLS = "TLS";
    /** SSL protocol: supports RFC 2246: TLS version 1.0 ; may support other versions. */
    public static final String PROTOCOL_TLS1 = "TLSv1";
    /** SSL protocol: supports RFC 4346: TLS version 1.1 ; may support other versions. */
    public static final String PROTOCOL_TLS1_1 = "TLSv1.1";
    /** SSL protocol: supports RFC 5246: TLS version 1.2 ; may support other versions. */
    public static final String PROTOCOL_TLS1_2 = "TLSv1.2";

    private TrustManager trustManager;
    private KeyManager keyManager;
    private String protocol = PROTOCOL_TLS1_2;
    private SecureRandom random;

    /** These are mutually exclusive. */
    private Provider provider;
    private String providerName;

    /** Creates a new SSL context builder using default parameters. */
    public SSLContextBuilder() {
        try {
            keyManager = KeyManagers.useJvmDefaultKeyStore();
        } catch (GeneralSecurityException | IOException ex) {
            keyManager = null;
        }
    }

    /**
     * Creates a {@code SSLContext} using the parameters of this SSL context
     * builder.
     *
     * @return A {@code SSLContext} using the parameters of this SSL context
     *         builder.
     * @throws GeneralSecurityException
     *             If the SSL context could not be created, perhaps due to
     *             missing algorithms.
     */
    public SSLContext getSSLContext() throws GeneralSecurityException {
        SSLContext sslContext = getInstance();
        sslContext.init(getKeyManagers(), getTrustManagers(), random);
        return sslContext;
    }

    private SSLContext getInstance() throws GeneralSecurityException {
        if (provider != null) {
            return SSLContext.getInstance(protocol, provider);
        } else if (providerName != null) {
            return SSLContext.getInstance(protocol, providerName);
        } else {
            return SSLContext.getInstance(protocol);
        }
    }

    private KeyManager[] getKeyManagers() {
        return keyManager != null ? new KeyManager[] { keyManager } : null;
    }

    private TrustManager[] getTrustManagers() {
        return trustManager != null ? new TrustManager[] { trustManager } : null;
    }

    /**
     * Sets the key manager which the SSL context should use. By default, the JVM's key manager is used.
     *
     * @param keyManager
     *            The key manager which the SSL context should use, which may be {@code null} indicating that no
     *            certificates will be used.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setKeyManager(final KeyManager keyManager) {
        this.keyManager = keyManager;
        return this;
    }

    /**
     * Sets the protocol which the SSL context should use. By default, TLSv1.2
     * will be used.
     *
     * @param protocol
     *            The protocol which the SSL context should use, which may be
     *            {@code null} indicating that TLSv1.2 will be used.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setProtocol(final String protocol) {
        this.protocol = protocol != null ? protocol : PROTOCOL_TLS1_2;
        return this;
    }

    /**
     * Sets the provider which the SSL context should use. By default, the
     * default provider associated with this JVM will be used.
     *
     * @param provider
     *            The provider which the SSL context should use, which may be
     *            {@code null} indicating that the default provider associated
     *            with this JVM will be used.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setProvider(final Provider provider) {
        this.provider = provider;
        this.providerName = null;
        return this;
    }

    /**
     * Sets the provider which the SSL context should use. By default, the
     * default provider associated with this JVM will be used.
     *
     * @param providerName
     *            The name of the provider which the SSL context should use,
     *            which may be {@code null} indicating that the default provider
     *            associated with this JVM will be used.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setProvider(final String providerName) {
        this.provider = null;
        this.providerName = providerName;
        return this;
    }

    /**
     * Sets the secure random number generator which the SSL context should use.
     * By default, the default secure random number generator associated with
     * this JVM will be used.
     *
     * @param random
     *            The secure random number generator which the SSL context
     *            should use, which may be {@code null} indicating that the
     *            default secure random number generator associated with this
     *            JVM will be used.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setSecureRandom(final SecureRandom random) {
        this.random = random;
        return this;
    }

    /**
     * Sets the trust manager which the SSL context should use. By default, no
     * trust manager is specified indicating that only certificates signed by
     * the authorities associated with this JVM will be accepted.
     *
     * @param trustManager
     *            The trust manager which the SSL context should use, which may
     *            be {@code null} indicating that only certificates signed by
     *            the authorities associated with this JVM will be accepted.
     * @return This SSL context builder.
     */
    public SSLContextBuilder setTrustManager(final TrustManager trustManager) {
        this.trustManager = trustManager;
        return this;
    }
}
