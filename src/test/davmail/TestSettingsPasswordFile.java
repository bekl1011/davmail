/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2026  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package davmail;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestSettingsPasswordFile extends TestCase {
    private static final String PASSWORD_PROPERTY = "davmail.test.password";
    private static final String PASSWORD_FILE_PROPERTY = "davmail.test.passwordFile";

    private String previousPassword;
    private String previousPasswordFile;
    private Path passwordFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        previousPassword = Settings.getProperty(PASSWORD_PROPERTY);
        previousPasswordFile = Settings.getProperty(PASSWORD_FILE_PROPERTY);
        Settings.setProperty(PASSWORD_PROPERTY, null);
        Settings.setProperty(PASSWORD_FILE_PROPERTY, null);
    }

    @Override
    protected void tearDown() throws Exception {
        Settings.setProperty(PASSWORD_PROPERTY, previousPassword);
        Settings.setProperty(PASSWORD_FILE_PROPERTY, previousPasswordFile);
        if (passwordFile != null) {
            Files.deleteIfExists(passwordFile);
        }
        super.tearDown();
    }

    public void testReadsUtf8PasswordFileAndStripsFinalNewlines() throws Exception {
        passwordFile = Files.createTempFile("davmail-password", ".txt");
        Files.write(passwordFile, "sécret\r\n".getBytes(StandardCharsets.UTF_8));
        Settings.setProperty(PASSWORD_FILE_PROPERTY, passwordFile.toString());

        assertEquals("sécret", new String(Settings.getCharArrayProperty(
                PASSWORD_PROPERTY, PASSWORD_FILE_PROPERTY)));
    }

    public void testInlinePasswordTakesPrecedence() throws Exception {
        Settings.setProperty(PASSWORD_PROPERTY, "inline-secret");
        Settings.setProperty(PASSWORD_FILE_PROPERTY, "/missing/password/file");

        assertEquals("inline-secret", new String(Settings.getCharArrayProperty(
                PASSWORD_PROPERTY, PASSWORD_FILE_PROPERTY)));
    }

    public void testPreservesWhitespaceOtherThanFinalNewlines() throws Exception {
        passwordFile = Files.createTempFile("davmail-password", ".txt");
        Files.write(passwordFile, " leading and trailing \n".getBytes(StandardCharsets.UTF_8));
        Settings.setProperty(PASSWORD_FILE_PROPERTY, passwordFile.toString());

        assertEquals(" leading and trailing ", new String(Settings.getCharArrayProperty(
                PASSWORD_PROPERTY, PASSWORD_FILE_PROPERTY)));
    }

    public void testMissingConfiguredPasswordFileFails() throws Exception {
        Settings.setProperty(PASSWORD_FILE_PROPERTY, "/missing/password/file");
        try {
            Settings.getCharArrayProperty(PASSWORD_PROPERTY, PASSWORD_FILE_PROPERTY);
            fail("Expected missing password file failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("/missing/password/file"));
        }
    }
}
