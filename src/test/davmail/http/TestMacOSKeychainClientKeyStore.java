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
import junit.framework.TestCase;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyStoreBuilderParameters;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class TestMacOSKeychainClientKeyStore extends TestCase {
    public void testKeychainStoreTypeSelectsNativeProvider() throws Exception {
        assertTrue(ClientKeyStoreProviderFactory.create(
                "KeychainStore", "", true) instanceof MacOSKeychainClientKeyStoreProvider);
    }

    public void testKeychainStoreIsRejectedOutsideMacOS() throws Exception {
        try {
            ClientKeyStoreProviderFactory.create("KeychainStore", "", false);
            fail("Expected unsupported platform failure");
        } catch (KeyStoreException expected) {
            assertTrue(expected.getMessage().contains("only on macOS"));
        }
    }

    public void testLegacyModesRemainOnExistingPaths() throws Exception {
        assertNull(ClientKeyStoreProviderFactory.create("PKCS12", "", false));
        assertNull(ClientKeyStoreProviderFactory.create("JKS", "", false));
        assertNull(ClientKeyStoreProviderFactory.create("PKCS11", "", false));
        assertNull(ClientKeyStoreProviderFactory.create("MSCAPI", "", false));
    }

    public void testUnknownKeyStoreTypeIsRejected() throws Exception {
        try {
            ClientKeyStoreProviderFactory.create("unknown", "", true);
            fail("Expected unknown keystore failure");
        } catch (KeyStoreException expected) {
            assertTrue(expected.getMessage().contains("Unsupported client keystore type"));
        }
    }

    public void testNativeModeDoesNotReadFileOrPassword() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addIdentity("identity", true);
        ClientKeyStoreProvider provider = new MacOSKeychainClientKeyStoreProvider(
                "", () -> keyStore);
        ArrayList<KeyStore.Builder> builders = new ArrayList<>();
        DavGatewaySSLSocketFactory.addConfiguredClientKeyStore(builders,
                "KeychainStore", provider,
                () -> {
                    throw new AssertionError("File property must not be read");
                },
                () -> {
                    throw new AssertionError("Password protection must not be created");
                });
        assertEquals(1, builders.size());
    }

    public void testFileModeStillReadsConfiguredFileAndPassword() throws Exception {
        AtomicInteger fileReads = new AtomicInteger();
        AtomicInteger passwordReads = new AtomicInteger();
        File file = File.createTempFile("davmail-client-keystore", ".test");
        try {
            ArrayList<KeyStore.Builder> builders = new ArrayList<>();
            DavGatewaySSLSocketFactory.addConfiguredClientKeyStore(builders,
                    "PKCS12", null,
                    () -> {
                        fileReads.incrementAndGet();
                        return file.getPath();
                    },
                    () -> {
                        passwordReads.incrementAndGet();
                        return new KeyStore.PasswordProtection(
                                "legacy-password".toCharArray());
                    });
            assertEquals(1, builders.size());
            assertEquals(1, fileReads.get());
            assertEquals(1, passwordReads.get());
        } finally {
            assertTrue(file.delete());
        }
    }

    public void testExplicitAliasIsValidated() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addIdentity("wanted", true);
        KeyStore.Builder builder = new MacOSKeychainClientKeyStoreProvider(
                "wanted", () -> keyStore).getKeyStoreBuilder();
        assertSame(keyStore, builder.getKeyStore());
    }

    public void testUnknownAliasProducesClearError() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addIdentity("available", true);
        try {
            new MacOSKeychainClientKeyStoreProvider("missing", () -> keyStore)
                    .getKeyStoreBuilder();
            fail("Expected alias failure");
        } catch (KeyStoreException expected) {
            assertTrue(expected.getMessage().contains("alias not found"));
        }
    }

    public void testCertificateOnlyAliasIsRejected() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addCertificate("certificate-only", true);
        try {
            new MacOSKeychainClientKeyStoreProvider("certificate-only", () -> keyStore)
                    .getKeyStoreBuilder();
            fail("Expected missing private key failure");
        } catch (KeyStoreException expected) {
            assertTrue(expected.getMessage().contains("has no private key"));
        }
    }

    public void testNoUsableClientIdentityIsRejected() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addIdentity("server-only", false);
        try {
            new MacOSKeychainClientKeyStoreProvider("", () -> keyStore)
                    .getKeyStoreBuilder();
            fail("Expected no usable identity failure");
        } catch (KeyStoreException expected) {
            assertTrue(expected.getMessage().contains("No usable TLS client identity"));
        }
    }

    public void testNativeBuilderCreatesUsableKeyManager() throws Exception {
        TestKeyStore keyStore = createKeyStore();
        keyStore.spi.addIdentity("native-identity", true);
        KeyStore.Builder builder = new MacOSKeychainClientKeyStoreProvider(
                "native-identity", () -> keyStore).getKeyStoreBuilder();
        KeyManagerFactory factory = KeyManagerFactory.getInstance("NewSunX509");
        factory.init(new KeyStoreBuilderParameters(builder));
        boolean usablePrivateKey = false;
        for (KeyManager keyManager : factory.getKeyManagers()) {
            if (keyManager instanceof X509KeyManager) {
                X509KeyManager x509KeyManager = (X509KeyManager) keyManager;
                String[] aliases = x509KeyManager.getClientAliases("RSA", null);
                if (aliases != null) {
                    for (String alias : aliases) {
                        usablePrivateKey |= x509KeyManager.getPrivateKey(alias) != null;
                    }
                }
            }
        }
        assertTrue(usablePrivateKey);
    }

    public void testConfiguredAliasSelectsMatchingBuilderAlias() throws Exception {
        StubX509KeyManager delegate = new StubX509KeyManager("1.0.first", "1.1.wanted");
        DavMailX509KeyManager keyManager = new DavMailX509KeyManager(delegate, "wanted");
        assertEquals("1.1.wanted", keyManager.chooseClientAlias(
                new String[]{"RSA"}, new Principal[0], null));
    }

    public void testConfiguredAliasMissingFromServerCandidatesFailsClearly() throws Exception {
        DavMailX509KeyManager keyManager = new DavMailX509KeyManager(
                new StubX509KeyManager("1.0.available"), "missing");
        try {
            keyManager.chooseClientAlias(new String[]{"RSA"}, new Principal[0], null);
            fail("Expected unsuitable alias failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not suitable"));
        }
    }

    public void testNativePrivateKeyAccessFailureIsClear() throws Exception {
        StubX509KeyManager delegate = new StubX509KeyManager("1.0.wanted");
        delegate.keys.clear();
        DavMailX509KeyManager keyManager = new DavMailX509KeyManager(
                delegate, "wanted", true);
        try {
            keyManager.getPrivateKey("1.0.wanted");
            fail("Expected Keychain access failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Keychain access denied"));
        }
    }

    public void testNativeServerMismatchFailsClearly() throws Exception {
        DavMailX509KeyManager keyManager = new DavMailX509KeyManager(
                new StubX509KeyManager(), "", true);
        try {
            keyManager.chooseClientAlias(new String[]{"RSA"}, new Principal[0], null);
            fail("Expected no suitable native identity failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No macOS Keychain client identity"));
        }
    }

    public void testMultipleAliasesUseExistingSelector() throws Exception {
        Settings.setProperty("davmail.server", "true");
        try {
            StubX509KeyManager delegate = new StubX509KeyManager("1.0.first", "1.1.second");
            SelectingKeyManager keyManager = new SelectingKeyManager(delegate);
            assertEquals("1.1.second", keyManager.chooseClientAlias(
                    new String[]{"RSA"}, new Principal[0], null));
            assertTrue(keyManager.selectorCalled);
        } finally {
            Settings.setProperty("davmail.server", "false");
        }
    }

    private TestKeyStore createKeyStore() throws Exception {
        TestKeyStore keyStore = new TestKeyStore();
        keyStore.load(null, null);
        return keyStore;
    }

    private static final class SelectingKeyManager extends DavMailX509KeyManager {
        private boolean selectorCalled;

        private SelectingKeyManager(X509KeyManager keyManager) {
            super(keyManager, "");
        }

        @Override
        protected String chooseClientAlias(String[] aliasesArray, String[] descriptionsArray) {
            selectorCalled = true;
            return aliasesArray[1];
        }
    }

    private static final class StubX509KeyManager implements X509KeyManager {
        private final String[] aliases;
        private final Map<String, PrivateKey> keys = new LinkedHashMap<>();
        private final Map<String, X509Certificate[]> chains = new LinkedHashMap<>();

        private StubX509KeyManager(String... aliases) throws Exception {
            this.aliases = aliases;
            for (String alias : aliases) {
                KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
                keys.put(alias, keyPair.getPrivate());
                chains.put(alias, new X509Certificate[]{
                        new TestCertificate(alias, keyPair.getPublic(), true)});
            }
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return aliases.clone();
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chains.get(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return keys.get(alias);
        }
    }

    private static final class TestKeyStore extends KeyStore {
        private static final Provider PROVIDER = new Provider("DavMailTest", 1.0,
                "DavMail test provider") { };
        private final TestKeyStoreSpi spi;

        private TestKeyStore() {
            this(new TestKeyStoreSpi());
        }

        private TestKeyStore(TestKeyStoreSpi spi) {
            super(spi, PROVIDER, "DavMailTest");
            this.spi = spi;
        }
    }

    private static final class TestKeyStoreSpi extends KeyStoreSpi {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        private void addIdentity(String alias, boolean clientAuth) throws Exception {
            KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            entries.put(alias, new Entry(keyPair.getPrivate(),
                    new Certificate[]{new TestCertificate(alias, keyPair.getPublic(), clientAuth)}));
        }

        private void addCertificate(String alias, boolean clientAuth) throws Exception {
            KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            entries.put(alias, new Entry(null,
                    new Certificate[]{new TestCertificate(alias, keyPair.getPublic(), clientAuth)}));
        }

        @Override
        public Key engineGetKey(String alias, char[] password) {
            Entry entry = entries.get(alias);
            return entry == null ? null : entry.privateKey;
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            Entry entry = entries.get(alias);
            return entry == null ? null : entry.chain.clone();
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            Entry entry = entries.get(alias);
            return entry == null ? null : entry.chain[0];
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return entries.containsKey(alias) ? new Date() : null;
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
            entries.put(alias, new Entry((PrivateKey) key, chain));
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain)
                throws KeyStoreException {
            throw new KeyStoreException("Not supported");
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert) {
            entries.put(alias, new Entry(null, new Certificate[]{cert}));
        }

        @Override
        public void engineDeleteEntry(String alias) {
            entries.remove(alias);
        }

        @Override
        public Enumeration<String> engineAliases() {
            return Collections.enumeration(entries.keySet());
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return entries.containsKey(alias);
        }

        @Override
        public int engineSize() {
            return entries.size();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            Entry entry = entries.get(alias);
            return entry != null && entry.privateKey != null;
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            Entry entry = entries.get(alias);
            return entry != null && entry.privateKey == null;
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().chain[0].equals(cert)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        @Override
        public void engineStore(OutputStream stream, char[] password) {
        }

        @Override
        public void engineLoad(InputStream stream, char[] password) {
        }

        private static final class Entry {
            private final PrivateKey privateKey;
            private final Certificate[] chain;

            private Entry(PrivateKey privateKey, Certificate[] chain) {
                this.privateKey = privateKey;
                this.chain = chain.clone();
            }
        }
    }

    private static final class TestCertificate extends X509Certificate {
        private final String alias;
        private final PublicKey publicKey;
        private final boolean clientAuth;

        private TestCertificate(String alias, PublicKey publicKey, boolean clientAuth) {
            this.alias = alias;
            this.publicKey = publicKey;
            this.clientAuth = clientAuth;
        }

        @Override
        public void checkValidity() {
        }

        @Override
        public void checkValidity(Date date) {
        }

        @Override
        public int getVersion() {
            return 3;
        }

        @Override
        public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }

        @Override
        public Principal getIssuerDN() {
            return getIssuerX500Principal();
        }

        @Override
        public Principal getSubjectDN() {
            return getSubjectX500Principal();
        }

        @Override
        public X500Principal getIssuerX500Principal() {
            return new X500Principal("CN=DavMail Test CA");
        }

        @Override
        public X500Principal getSubjectX500Principal() {
            return new X500Principal("CN=" + alias);
        }

        @Override
        public Date getNotBefore() {
            return new Date(0);
        }

        @Override
        public Date getNotAfter() {
            return new Date(Long.MAX_VALUE);
        }

        @Override
        public byte[] getTBSCertificate() {
            return new byte[]{1};
        }

        @Override
        public byte[] getSignature() {
            return new byte[]{1};
        }

        @Override
        public String getSigAlgName() {
            return "NONE";
        }

        @Override
        public String getSigAlgOID() {
            return "1.2.3";
        }

        @Override
        public byte[] getSigAlgParams() {
            return null;
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return null;
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return null;
        }

        @Override
        public boolean[] getKeyUsage() {
            return new boolean[]{true, false, false, false, false};
        }

        @Override
        public List<String> getExtendedKeyUsage() {
            return Collections.singletonList(clientAuth
                    ? MacOSKeychainClientKeyStoreProvider.CLIENT_AUTH_OID
                    : "1.3.6.1.5.5.7.3.1");
        }

        @Override
        public int getBasicConstraints() {
            return -1;
        }

        @Override
        public byte[] getEncoded() {
            return publicKey.getEncoded();
        }

        @Override
        public void verify(PublicKey key) {
        }

        @Override
        public void verify(PublicKey key, String sigProvider) {
        }

        @Override
        public String toString() {
            return getSubjectX500Principal().toString();
        }

        @Override
        public PublicKey getPublicKey() {
            return publicKey;
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }

        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return null;
        }
    }
}
