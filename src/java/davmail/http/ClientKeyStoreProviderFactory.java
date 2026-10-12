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

import davmail.Settings;

import java.security.KeyStoreException;

/** Selects an explicitly configured platform-native client identity store. */
final class ClientKeyStoreProviderFactory {
    private ClientKeyStoreProviderFactory() {
    }

    static ClientKeyStoreProvider create(String keyStoreType, String certificateAlias)
            throws KeyStoreException {
        return create(keyStoreType, certificateAlias, Settings.isOSX());
    }

    static ClientKeyStoreProvider create(String keyStoreType, String certificateAlias,
                                         boolean macOS) throws KeyStoreException {
        if (MacOSKeychainClientKeyStoreProvider.KEYCHAIN_STORE.equals(keyStoreType)) {
            if (!macOS) {
                throw new KeyStoreException(
                        "KeychainStore client keystore is supported only on macOS");
            }
            return new MacOSKeychainClientKeyStoreProvider(certificateAlias);
        }
        if (!"PKCS11".equals(keyStoreType)
                && !"PKCS12".equals(keyStoreType)
                && !"JKS".equals(keyStoreType)
                && !"MSCAPI".equals(keyStoreType)) {
            throw new KeyStoreException("Unsupported client keystore type: " + keyStoreType);
        }

        // Future Linux native-store support belongs here and should expose an
        // NSS/p11-kit backed PKCS11 KeyStore through ClientKeyStoreProvider.
        return null;
    }
}
