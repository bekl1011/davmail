/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2026  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package davmail.http;

import org.apache.log4j.Logger;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Loads TLS client identities from the current user's macOS Keychain. */
final class MacOSKeychainClientKeyStoreProvider implements ClientKeyStoreProvider {
    static final String KEYCHAIN_STORE = "KeychainStore";
    static final String CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2";
    static final String ANY_EXTENDED_KEY_USAGE_OID = "2.5.29.37.0";

    private static final Logger LOGGER = Logger.getLogger(MacOSKeychainClientKeyStoreProvider.class);

    interface KeyStoreLoader {
        KeyStore load() throws Exception;
    }

    private final String configuredAlias;
    private final KeyStoreLoader keyStoreLoader;

    MacOSKeychainClientKeyStoreProvider(String configuredAlias) {
        this(configuredAlias, () -> {
            KeyStore keyStore = KeyStore.getInstance(KEYCHAIN_STORE);
            keyStore.load(null, null);
            return keyStore;
        });
    }

    MacOSKeychainClientKeyStoreProvider(String configuredAlias, KeyStoreLoader keyStoreLoader) {
        this.configuredAlias = configuredAlias == null ? "" : configuredAlias.trim();
        this.keyStoreLoader = keyStoreLoader;
    }

    @Override
    public KeyStore.Builder getKeyStoreBuilder() throws KeyStoreException {
        LOGGER.info("Loading TLS client identities from macOS Keychain");
        final KeyStore keyStore;
        try {
            keyStore = keyStoreLoader.load();
        } catch (Exception e) {
            throw new KeyStoreException("Unable to load macOS KeychainStore", e);
        }

        List<String> usableAliases = getUsableAliases(keyStore);
        if (!configuredAlias.isEmpty()) {
            String keyStoreAlias = findAlias(keyStore, configuredAlias);
            if (keyStoreAlias == null) {
                throw new KeyStoreException(
                        "Configured client certificate alias not found: " + configuredAlias);
            }
            if (!keyStore.isKeyEntry(keyStoreAlias)) {
                throw new KeyStoreException(
                        "Configured client certificate alias has no private key: " + configuredAlias);
            }
            if (!usableAliases.contains(keyStoreAlias)) {
                throw new KeyStoreException(
                        "Configured client certificate alias is not a usable TLS client identity: "
                                + configuredAlias);
            }
        }
        if (usableAliases.isEmpty()) {
            throw new KeyStoreException("No usable TLS client identity found in macOS Keychain");
        }

        LOGGER.info("Loaded macOS Keychain client keystore with "
                + usableAliases.size()
                + (usableAliases.size() == 1
                ? " usable client identity"
                : " usable client identities"));
        // The Apple provider ignores the load password. For private-key access,
        // a null PasswordProtection lets the provider apply normal Keychain ACLs
        // and display any authorization dialog required by macOS.
        return KeyStore.Builder.newInstance(keyStore, new KeyStore.PasswordProtection(null));
    }

    private List<String> getUsableAliases(KeyStore keyStore) throws KeyStoreException {
        List<String> aliases = new ArrayList<>();
        Enumeration<String> keyStoreAliases = keyStore.aliases();
        while (keyStoreAliases.hasMoreElements()) {
            String alias = keyStoreAliases.nextElement();
            if (isUsableClientIdentity(keyStore, alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    static boolean isUsableClientIdentity(KeyStore keyStore, String alias)
            throws KeyStoreException {
        if (!keyStore.isKeyEntry(alias)) {
            return false;
        }
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            return false;
        }
        X509Certificate certificate = (X509Certificate) chain[0];
        try {
            certificate.checkValidity();
            List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
            if (extendedKeyUsage != null
                    && !extendedKeyUsage.contains(CLIENT_AUTH_OID)
                    && !extendedKeyUsage.contains(ANY_EXTENDED_KEY_USAGE_OID)) {
                return false;
            }
        } catch (CertificateException e) {
            return false;
        }
        boolean[] keyUsage = certificate.getKeyUsage();
        return keyUsage == null
                || (keyUsage.length > 0 && keyUsage[0])
                || (keyUsage.length > 4 && keyUsage[4]);
    }

    private String findAlias(KeyStore keyStore, String requestedAlias)
            throws KeyStoreException {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (alias.equals(requestedAlias) || alias.equalsIgnoreCase(requestedAlias)) {
                return alias;
            }
        }
        return null;
    }
}
