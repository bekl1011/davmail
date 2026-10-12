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

import java.security.KeyStore;
import java.security.KeyStoreException;

/** Loads one platform-native client identity store for JSSE. */
interface ClientKeyStoreProvider {
    KeyStore.Builder getKeyStoreBuilder() throws KeyStoreException;
}
