/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.fingerprint

import org.apache.nifi.encrypt.PropertyEncryptor
import org.apache.nifi.encrypt.SensitiveValueEncoder
import org.apache.nifi.nar.ExtensionManager
import org.apache.nifi.nar.StandardExtensionDiscoveringManager
import org.apache.nifi.util.NiFiProperties
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.security.Security

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class FingerprintFactoryGroovyIT {
    private static final Logger logger = LoggerFactory.getLogger(FingerprintFactoryGroovyIT.class)

    private static PropertyEncryptor mockEncryptor = [
            encrypt: { String plaintext -> plaintext.reverse() },
            decrypt: { String cipherText -> cipherText.reverse() }] as PropertyEncryptor
    private static SensitiveValueEncoder mockSensitiveValueEncoder = [
            getEncoded: { String plaintext -> "[MASKED] (${plaintext.sha256()})".toString() }] as SensitiveValueEncoder
    private static ExtensionManager extensionManager = new StandardExtensionDiscoveringManager()

    private static String originalPropertiesPath = System.getProperty(NiFiProperties.PROPERTIES_FILE_PATH)
    private static final String NIFI_PROPERTIES_PATH = "src/test/resources/conf/nifi.properties"

    @BeforeAll
    static void setUpOnce() throws Exception {
        Security.addProvider(new BouncyCastleProvider())

        logger.metaClass.methodMissing = { String name, args ->
            logger.info("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }
    }

    @AfterAll
    static void tearDownOnce() {
        if (originalPropertiesPath) {
            System.setProperty(NiFiProperties.PROPERTIES_FILE_PATH, originalPropertiesPath)
        }
    }

    /**
     * The initial implementation derived the hashed value using a time/memory-hard algorithm (Argon2) every time.
     * For large flow definitions, this blocked startup for minutes. Deriving a secure key with the Argon2
     * algorithm once at startup (~1 second) and using this cached key for a simple HMAC/SHA-256 operation on every
     * fingerprint should be much faster.
     */
    @Test
    void testCreateFingerprintShouldNotBeSlow() {
        // Arrange
        int testIterations = 100 //_000

        // Set up test nifi.properties
        System.setProperty(NiFiProperties.PROPERTIES_FILE_PATH, NIFI_PROPERTIES_PATH)

        // Create flow
        String initialFlowXML = new File("src/test/resources/nifi/fingerprint/initial.xml").text
        logger.info("Read initial flow: ${initialFlowXML[0..<100]}...")

        // Create the FingerprintFactory with collaborators
        FingerprintFactory fingerprintFactory =
                new FingerprintFactory(mockEncryptor, extensionManager, mockSensitiveValueEncoder)

        List<String> results = []
        def resultDurations = []

        // Act
        testIterations.times { int i ->
            long startNanos = System.nanoTime()

            // Create the fingerprint from the flow
            String fingerprint = fingerprintFactory.createFingerprint(initialFlowXML.bytes)

            long endNanos = System.nanoTime()
            long durationNanos = endNanos - startNanos

            logger.info("Generated flow fingerprint: ${fingerprint} in ${durationNanos} ns")

            results << fingerprint
            resultDurations << durationNanos
        }

        def milliDurations = [resultDurations.min(), resultDurations.max(), resultDurations.sum() / resultDurations.size()].collect { it / 1_000_000 }
        logger.info("Min/Max/Avg durations in ms: ${milliDurations}")

        // Assert
        final long MAX_DURATION_NANOS = 1_000_000_000 // 1 second
        assertTrue(resultDurations.max() <= MAX_DURATION_NANOS * 2)
        assertTrue(resultDurations.sum() / testIterations < MAX_DURATION_NANOS)

        // Assert the fingerprint does not contain the password
        results.forEach(fingerprint -> {
            assertFalse(fingerprint.contains("originalPlaintextPassword"))
            def maskedValue = (fingerprint =~ /\[MASKED\] \([\w\/\+=]+\)/)
            assertTrue(maskedValue.find())
            logger.info("Masked value: ${maskedValue[0]}")
        })
    }
}
