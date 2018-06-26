/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.apksigner;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import com.android.apksig.SigningCertificateLineage.SignerCapabilities;
import com.android.apksig.apk.MinSdkVersionException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Command-line tool for signing APKs and for checking whether an APK's signature are expected to
 * verify on Android devices.
 */
public class ApkSignerTool {

    private static final String VERSION = "0.9";
    private static final String HELP_PAGE_GENERAL = "help.txt";
    private static final String HELP_PAGE_SIGN = "help_sign.txt";
    private static final String HELP_PAGE_VERIFY = "help_verify.txt";
    private static final String HELP_PAGE_ROTATE = "help_rotate.txt";
    private static final String HELP_PAGE_LINEAGE = "help_lineage.txt";

    private static MessageDigest sha256 = null;
    private static MessageDigest sha1 = null;
    private static MessageDigest md5 = null;

    public static void main(String[] params) throws Exception {
        if ((params.length == 0) || ("--help".equals(params[0])) || ("-h".equals(params[0]))) {
            printUsage(HELP_PAGE_GENERAL);
            return;
        } else if ("--version".equals(params[0])) {
            System.out.println(VERSION);
            return;
        }

        String cmd = params[0];
        try {
            if ("sign".equals(cmd)) {
                sign(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("verify".equals(cmd)) {
                verify(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("rotate".equals(cmd)) {
                rotate(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("lineage".equals(cmd)) {
                lineage(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("help".equals(cmd)) {
                printUsage(HELP_PAGE_GENERAL);
                return;
            } else if ("version".equals(cmd)) {
                System.out.println(VERSION);
                return;
            } else {
                throw new ParameterException(
                        "Unsupported command: " + cmd + ". See --help for supported commands");
            }
        } catch (ParameterException | OptionsParser.OptionsException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
    }

    private static void sign(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_SIGN);
            return;
        }

        File outputApk = null;
        File inputApk = null;
        boolean verbose = false;
        boolean v1SigningEnabled = true;
        boolean v2SigningEnabled = true;
        boolean v3SigningEnabled = true;
        boolean debuggableApkPermitted = true;
        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        int maxSdkVersion = Integer.MAX_VALUE;
        List<SignerParams> signers = new ArrayList<>(1);
        SignerParams signerParams = new SignerParams();
        SigningCertificateLineage lineage = null;
        List<ProviderInstallSpec> providers = new ArrayList<>();
        ProviderInstallSpec providerParams = new ProviderInstallSpec();
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        String optionOriginalForm = null;
        while ((optionName = optionsParser.nextOption()) != null) {
            optionOriginalForm = optionsParser.getOptionOriginalForm();
            if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_SIGN);
                return;
            } else if ("out".equals(optionName)) {
                outputApk = new File(optionsParser.getRequiredValue("Output file name"));
            } else if ("in".equals(optionName)) {
                inputApk = new File(optionsParser.getRequiredValue("Input file name"));
            } else if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                minSdkVersionSpecified = true;
            } else if ("max-sdk-version".equals(optionName)) {
                maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
            } else if ("v1-signing-enabled".equals(optionName)) {
                v1SigningEnabled = optionsParser.getOptionalBooleanValue(true);
            } else if ("v2-signing-enabled".equals(optionName)) {
                v2SigningEnabled = optionsParser.getOptionalBooleanValue(true);
            } else if ("v3-signing-enabled".equals(optionName)) {
                v3SigningEnabled = optionsParser.getOptionalBooleanValue(true);
            } else if ("debuggable-apk-permitted".equals(optionName)) {
                debuggableApkPermitted = optionsParser.getOptionalBooleanValue(true);
            } else if ("next-signer".equals(optionName)) {
                if (!signerParams.isEmpty()) {
                    signers.add(signerParams);
                    signerParams = new SignerParams();
                }
            } else if ("ks".equals(optionName)) {
                signerParams.keystoreFile = optionsParser.getRequiredValue("KeyStore file");
            } else if ("ks-key-alias".equals(optionName)) {
                signerParams.keystoreKeyAlias =
                        optionsParser.getRequiredValue("KeyStore key alias");
            } else if ("ks-pass".equals(optionName)) {
                signerParams.keystorePasswordSpec =
                        optionsParser.getRequiredValue("KeyStore password");
            } else if ("key-pass".equals(optionName)) {
                signerParams.keyPasswordSpec = optionsParser.getRequiredValue("Key password");
            } else if ("pass-encoding".equals(optionName)) {
                String charsetName =
                        optionsParser.getRequiredValue("Password character encoding");
                try {
                    signerParams.passwordCharset = PasswordRetriever.getCharsetByName(charsetName);
                } catch (IllegalArgumentException e) {
                    throw new ParameterException(
                            "Unsupported password character encoding requested using"
                                    + " --pass-encoding: " + charsetName);
                }
            } else if ("v1-signer-name".equals(optionName)) {
                signerParams.v1SigFileBasename =
                        optionsParser.getRequiredValue("JAR signature file basename");
            } else if ("ks-type".equals(optionName)) {
                signerParams.keystoreType = optionsParser.getRequiredValue("KeyStore type");
            } else if ("ks-provider-name".equals(optionName)) {
                signerParams.keystoreProviderName =
                        optionsParser.getRequiredValue("JCA KeyStore Provider name");
            } else if ("ks-provider-class".equals(optionName)) {
                signerParams.keystoreProviderClass =
                        optionsParser.getRequiredValue("JCA KeyStore Provider class name");
            } else if ("ks-provider-arg".equals(optionName)) {
                signerParams.keystoreProviderArg =
                        optionsParser.getRequiredValue(
                                "JCA KeyStore Provider constructor argument");
            } else if ("key".equals(optionName)) {
                signerParams.keyFile = optionsParser.getRequiredValue("Private key file");
            } else if ("cert".equals(optionName)) {
                signerParams.certFile = optionsParser.getRequiredValue("Certificate file");
            } else if ("lineage".equals(optionName)) {
                File lineageFile = new File(optionsParser.getRequiredValue("Lineage File"));
                lineage = SigningCertificateLineage.readFromFile(lineageFile);
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("next-provider".equals(optionName)) {
                if (!providerParams.isEmpty()) {
                    providers.add(providerParams);
                    providerParams = new ProviderInstallSpec();
                }
            } else if ("provider-class".equals(optionName)) {
                providerParams.className =
                        optionsParser.getRequiredValue("JCA Provider class name");
            } else if ("provider-arg".equals(optionName)) {
                providerParams.constructorParam =
                        optionsParser.getRequiredValue("JCA Provider constructor argument");
            } else if ("provider-pos".equals(optionName)) {
                providerParams.position =
                        optionsParser.getRequiredIntValue("JCA Provider position");
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        if (!signerParams.isEmpty()) {
            signers.add(signerParams);
        }
        signerParams = null;
        if (!providerParams.isEmpty()) {
            providers.add(providerParams);
        }
        providerParams = null;

        if (signers.isEmpty()) {
            throw new ParameterException("At least one signer must be specified");
        }

        params = optionsParser.getRemainingParams();
        if (inputApk != null) {
            // Input APK has been specified via preceding parameters. We don't expect any more
            // parameters.
            if (params.length > 0) {
                throw new ParameterException(
                        "Unexpected parameter(s) after " + optionOriginalForm + ": " + params[0]);
            }
        } else {
            // Input APK has not been specified via preceding parameters. The next parameter is
            // supposed to be the path to input APK.
            if (params.length < 1) {
                throw new ParameterException("Missing input APK");
            } else if (params.length > 1) {
                throw new ParameterException(
                        "Unexpected parameter(s) after input APK (" + params[1] + ")");
            }
            inputApk = new File(params[0]);
        }
        if ((minSdkVersionSpecified) && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        // Install additional JCA Providers
        for (ProviderInstallSpec providerInstallSpec : providers) {
            providerInstallSpec.installProvider();
        }

        List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>(signers.size());
        int signerNumber = 0;
        try (PasswordRetriever passwordRetriever = new PasswordRetriever()) {
            for (SignerParams signer : signers) {
                signerNumber++;
                signer.name = "signer #" + signerNumber;
                try {
                    signer.loadPrivateKeyAndCerts(passwordRetriever);
                } catch (ParameterException e) {
                    System.err.println(
                            "Failed to load signer \"" + signer.name + "\": "
                                    + e.getMessage());
                    System.exit(2);
                    return;
                } catch (Exception e) {
                    System.err.println("Failed to load signer \"" + signer.name + "\"");
                    e.printStackTrace();
                    System.exit(2);
                    return;
                }
                String v1SigBasename;
                if (signer.v1SigFileBasename != null) {
                    v1SigBasename = signer.v1SigFileBasename;
                } else if (signer.keystoreKeyAlias != null) {
                    v1SigBasename = signer.keystoreKeyAlias;
                } else if (signer.keyFile != null) {
                    String keyFileName = new File(signer.keyFile).getName();
                    int delimiterIndex = keyFileName.indexOf('.');
                    if (delimiterIndex == -1) {
                        v1SigBasename = keyFileName;
                    } else {
                        v1SigBasename = keyFileName.substring(0, delimiterIndex);
                    }
                } else {
                    throw new RuntimeException(
                            "Neither KeyStore key alias nor private key file available");
                }
                ApkSigner.SignerConfig signerConfig =
                        new ApkSigner.SignerConfig.Builder(
                                v1SigBasename, signer.privateKey, signer.certs)
                        .build();
                signerConfigs.add(signerConfig);
            }
        }

        if (outputApk == null) {
            outputApk = inputApk;
        }
        File tmpOutputApk;
        if (inputApk.getCanonicalPath().equals(outputApk.getCanonicalPath())) {
            tmpOutputApk = File.createTempFile("apksigner", ".apk");
            tmpOutputApk.deleteOnExit();
        } else {
            tmpOutputApk = outputApk;
        }
        ApkSigner.Builder apkSignerBuilder =
                new ApkSigner.Builder(signerConfigs)
                        .setInputApk(inputApk)
                        .setOutputApk(tmpOutputApk)
                        .setOtherSignersSignaturesPreserved(false)
                        .setV1SigningEnabled(v1SigningEnabled)
                        .setV2SigningEnabled(v2SigningEnabled)
                        .setV3SigningEnabled(v3SigningEnabled)
                        .setDebuggableApkPermitted(debuggableApkPermitted)
                        .setSigningCertificateLineage(lineage);
        if (minSdkVersionSpecified) {
            apkSignerBuilder.setMinSdkVersion(minSdkVersion);
        }
        ApkSigner apkSigner = apkSignerBuilder.build();
        try {
            apkSigner.sign();
        } catch (MinSdkVersionException e) {
            String msg = e.getMessage();
            if (!msg.endsWith(".")) {
                msg += '.';
            }
            throw new MinSdkVersionException(
                    "Failed to determine APK's minimum supported platform version"
                            + ". Use --min-sdk-version to override",
                    e);
        }
        if (!tmpOutputApk.getCanonicalPath().equals(outputApk.getCanonicalPath())) {
            Files.move(
                    tmpOutputApk.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        if (verbose) {
            System.out.println("Signed");
        }
    }

    private static void verify(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_VERIFY);
            return;
        }

        File inputApk = null;
        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        int maxSdkVersion = Integer.MAX_VALUE;
        boolean maxSdkVersionSpecified = false;
        boolean printCerts = false;
        boolean verbose = false;
        boolean warningsTreatedAsErrors = false;
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        String optionOriginalForm = null;
        while ((optionName = optionsParser.nextOption()) != null) {
            optionOriginalForm = optionsParser.getOptionOriginalForm();
            if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                minSdkVersionSpecified = true;
            } else if ("max-sdk-version".equals(optionName)) {
                maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
                maxSdkVersionSpecified = true;
            } else if ("print-certs".equals(optionName)) {
                printCerts = optionsParser.getOptionalBooleanValue(true);
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("Werr".equals(optionName)) {
                warningsTreatedAsErrors = optionsParser.getOptionalBooleanValue(true);
            } else if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_VERIFY);
                return;
            } else if ("in".equals(optionName)) {
                inputApk = new File(optionsParser.getRequiredValue("Input APK file"));
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        params = optionsParser.getRemainingParams();

        if (inputApk != null) {
            // Input APK has been specified in preceding parameters. We don't expect any more
            // parameters.
            if (params.length > 0) {
                throw new ParameterException(
                        "Unexpected parameter(s) after " + optionOriginalForm + ": " + params[0]);
            }
        } else {
            // Input APK has not been specified in preceding parameters. The next parameter is
            // supposed to be the input APK.
            if (params.length < 1) {
                throw new ParameterException("Missing APK");
            } else if (params.length > 1) {
                throw new ParameterException(
                        "Unexpected parameter(s) after APK (" + params[1] + ")");
            }
            inputApk = new File(params[0]);
        }

        if ((minSdkVersionSpecified) && (maxSdkVersionSpecified)
                && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        ApkVerifier.Builder apkVerifierBuilder = new ApkVerifier.Builder(inputApk);
        if (minSdkVersionSpecified) {
            apkVerifierBuilder.setMinCheckedPlatformVersion(minSdkVersion);
        }
        if (maxSdkVersionSpecified) {
            apkVerifierBuilder.setMaxCheckedPlatformVersion(maxSdkVersion);
        }
        ApkVerifier apkVerifier = apkVerifierBuilder.build();
        ApkVerifier.Result result;
        try {
            result = apkVerifier.verify();
        } catch (MinSdkVersionException e) {
            String msg = e.getMessage();
            if (!msg.endsWith(".")) {
                msg += '.';
            }
            throw new MinSdkVersionException(
                    "Failed to determine APK's minimum supported platform version"
                            + ". Use --min-sdk-version to override",
                    e);
        }
        boolean verified = result.isVerified();

        boolean warningsEncountered = false;
        if (verified) {
            List<X509Certificate> signerCerts = result.getSignerCertificates();
            if (verbose) {
                System.out.println("Verifies");
                System.out.println(
                        "Verified using v1 scheme (JAR signing): "
                                + result.isVerifiedUsingV1Scheme());
                System.out.println(
                        "Verified using v2 scheme (APK Signature Scheme v2): "
                                + result.isVerifiedUsingV2Scheme());
                System.out.println(
                        "Verified using v3 scheme (APK Signature Scheme v3): "
                                + result.isVerifiedUsingV3Scheme());
                System.out.println("Number of signers: " + signerCerts.size());
            }
            if (printCerts) {
                int signerNumber = 0;
                for (X509Certificate signerCert : signerCerts) {
                    signerNumber++;
                    printCertificate(signerCert, "Signer #" + signerNumber, verbose);
                }
            }
        } else {
            System.err.println("DOES NOT VERIFY");
        }

        for (ApkVerifier.IssueWithParams error : result.getErrors()) {
            System.err.println("ERROR: " + error);
        }

        @SuppressWarnings("resource") // false positive -- this resource is not opened here
        PrintStream warningsOut = (warningsTreatedAsErrors) ? System.err : System.out;
        for (ApkVerifier.IssueWithParams warning : result.getWarnings()) {
            warningsEncountered = true;
            warningsOut.println("WARNING: " + warning);
        }
        for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeSigners()) {
            String signerName = signer.getName();
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println("ERROR: JAR signer " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println("WARNING: JAR signer " + signerName + ": " + warning);
            }
        }
        for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
            String signerName = "signer #" + (signer.getIndex() + 1);
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println(
                        "ERROR: APK Signature Scheme v2 " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println(
                        "WARNING: APK Signature Scheme v2 " + signerName + ": " + warning);
            }
        }
        for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV3SchemeSigners()) {
            String signerName = "signer #" + (signer.getIndex() + 1);
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println(
                        "ERROR: APK Signature Scheme v3 " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println(
                        "WARNING: APK Signature Scheme v3 " + signerName + ": " + warning);
            }
        }

        if (!verified) {
            System.exit(1);
            return;
        }
        if ((warningsTreatedAsErrors) && (warningsEncountered)) {
            System.exit(1);
            return;
        }
    }

    private static void rotate(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_ROTATE);
            return;
        }

        File outputKeyLineage = null;
        File inputKeyLineage = null;
        boolean verbose = false;
        SignerParams oldSignerParams = null;
        SignerParams newSignerParams = null;
        int minSdkVersion = 0;
        List<ProviderInstallSpec> providers = new ArrayList<>();
        ProviderInstallSpec providerParams = new ProviderInstallSpec();
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        String optionOriginalForm = null;
        while ((optionName = optionsParser.nextOption()) != null) {
            optionOriginalForm = optionsParser.getOptionOriginalForm();
            if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_ROTATE);
                return;
            } else if ("out".equals(optionName)) {
                outputKeyLineage = new File(optionsParser.getRequiredValue("Output file name"));
            } else if ("in".equals(optionName)) {
                inputKeyLineage = new File(optionsParser.getRequiredValue("Input file name"));
            } else if ("old-signer".equals(optionName)) {
                oldSignerParams = processSignerParams(optionsParser);
            } else if ("new-signer".equals(optionName)) {
                newSignerParams = processSignerParams(optionsParser);
            } else if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("next-provider".equals(optionName)) {
                if (!providerParams.isEmpty()) {
                    providers.add(providerParams);
                    providerParams = new ProviderInstallSpec();
                }
            } else if ("provider-class".equals(optionName)) {
                providerParams.className =
                        optionsParser.getRequiredValue("JCA Provider class name");
            } else if ("provider-arg".equals(optionName)) {
                providerParams.constructorParam =
                        optionsParser.getRequiredValue("JCA Provider constructor argument");
            } else if ("provider-pos".equals(optionName)) {
                providerParams.position =
                        optionsParser.getRequiredIntValue("JCA Provider position");
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        if (!providerParams.isEmpty()) {
            providers.add(providerParams);
        }
        providerParams = null;

        if (oldSignerParams.isEmpty()) {
            throw new ParameterException("Signer parameters for old signer not present");
        }

        if (newSignerParams.isEmpty()) {
            throw new ParameterException("Signer parameters for new signer not present");
        }

        if (outputKeyLineage == null) {
            throw new ParameterException("Output lineage file parameter not present");
        }

        params = optionsParser.getRemainingParams();
        if (params.length > 0) {
            throw new ParameterException(
                    "Unexpected parameter(s) after " + optionOriginalForm + ": " + params[0]);
        }


        // Install additional JCA Providers
        for (ProviderInstallSpec providerInstallSpec : providers) {
            providerInstallSpec.installProvider();
        }

        try (PasswordRetriever passwordRetriever = new PasswordRetriever()) {
            // populate SignerConfig for old signer
            oldSignerParams.name = "old signer";
            loadPrivateKeyAndCerts(oldSignerParams, passwordRetriever);
            SigningCertificateLineage.SignerConfig oldSignerConfig =
                    new SigningCertificateLineage.SignerConfig.Builder(
                            oldSignerParams.privateKey, oldSignerParams.certs.get(0)).build();

            // TOOD: don't require private key
            newSignerParams.name = "new signer";
            loadPrivateKeyAndCerts(newSignerParams, passwordRetriever);
            SigningCertificateLineage.SignerConfig newSignerConfig =
                    new SigningCertificateLineage.SignerConfig.Builder(
                            newSignerParams.privateKey, newSignerParams.certs.get(0)).build();

            // ok we're all set up, let's rotate!
            SigningCertificateLineage lineage;
            if (inputKeyLineage != null) {
                // we already have history, add the new key to the end of it
                lineage = SigningCertificateLineage.readFromFile(inputKeyLineage);
                lineage.updateSignerCapabilities(oldSignerConfig,
                        oldSignerParams.signerCapabilitiesBuilder.build());
                lineage = lineage.spawnDescendant(oldSignerConfig,
                        newSignerConfig, newSignerParams.signerCapabilitiesBuilder.build());
            } else {
                // this is the first entry in our signing history, create a new one from the old and
                // new signer info
                lineage = new SigningCertificateLineage.Builder(oldSignerConfig, newSignerConfig)
                        .setMinSdkVersion(minSdkVersion)
                        .setOriginalCapabilities(oldSignerParams.signerCapabilitiesBuilder.build())
                        .setNewCapabilities(newSignerParams.signerCapabilitiesBuilder.build())
                        .build();
            }
            // and write out the result
            lineage.writeToFile(outputKeyLineage);
        }
        if (verbose) {
            System.out.println("Rotation entry generated.");
        }
    }

    public static void lineage(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_LINEAGE);
            return;
        }

        boolean verbose = false;
        boolean printCerts = false;
        boolean lineageUpdated = false;
        File outputKeyLineage = null;
        String optionName;
        OptionsParser optionsParser = new OptionsParser(params);
        SigningCertificateLineage lineage = null;
        List<SignerParams> signers = new ArrayList<>(1);
        while ((optionName = optionsParser.nextOption()) != null) {
            if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_LINEAGE);
                return;
            } else if ("in".equals(optionName)) {
                File inputKeyLineage = new File(optionsParser.getRequiredValue("Input file name"));
                lineage = SigningCertificateLineage.readFromFile(inputKeyLineage);
            } else if ("out".equals(optionName)) {
                outputKeyLineage = new File(optionsParser.getRequiredValue("Output file name"));
            } else if ("signer".equals(optionName)) {
                SignerParams signerParams = processSignerParams(optionsParser);
                signers.add(signerParams);
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("print-certs".equals(optionName)) {
                printCerts = optionsParser.getOptionalBooleanValue(true);
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionsParser.getOptionOriginalForm()
                                + ". See --help for supported options.");
            }
        }
        if (lineage == null) {
            throw new ParameterException("Input lineage file parameter not present");
        }
        try (PasswordRetriever passwordRetriever = new PasswordRetriever()) {
            for (int i = 0; i < signers.size(); i++) {
                SignerParams signerParams = signers.get(i);
                signerParams.name = "signer #" + (i + 1);
                loadPrivateKeyAndCerts(signerParams, passwordRetriever);
                SigningCertificateLineage.SignerConfig signerConfig =
                        new SigningCertificateLineage.SignerConfig.Builder(
                                signerParams.privateKey, signerParams.certs.get(0)).build();
                try {
                    // since only the caller specified capabilities will be updated a direct
                    // comparison between the original capabilities of the signer and the
                    // signerCapabilitiesBuilder object with potential default values is not
                    // possible. Instead the capabilities should be updated first, then the new
                    // capabilities can be compared against the original to determine if the
                    // lineage has been updated and needs to be written out to a file.
                    SignerCapabilities origCapabilities = lineage.getSignerCapabilities(
                            signerConfig);
                    lineage.updateSignerCapabilities(signerConfig,
                            signerParams.signerCapabilitiesBuilder.build());
                    SignerCapabilities newCapabilities = lineage.getSignerCapabilities(
                            signerConfig);
                    if (origCapabilities.equals(newCapabilities)) {
                        if (verbose) {
                            System.out.println(
                                    "The provided signer capabilities for " + signerParams.name
                                            + " are unchanged.");
                        }
                    } else {
                        lineageUpdated = true;
                        if (verbose) {
                            System.out.println(
                                    "Updated signer capabilities for " + signerParams.name + ".");
                        }
                    }
                } catch (IllegalArgumentException e) {
                    throw new ParameterException("The signer " + signerParams.name
                            + " was not found in the specified lineage.");
                }
            }
        }
        if (printCerts) {
            List<X509Certificate> signingCerts = lineage.getCertificatesInLineage();
            for (int i = 0; i < signingCerts.size(); i++) {
                X509Certificate signerCert = signingCerts.get(i);
                SignerCapabilities signerCapabilities = lineage.getSignerCapabilities(signerCert);
                printCertificate(signerCert, "Signer #" + (i + 1) + " in lineage", verbose);
                printCapabilities(signerCapabilities);
            }
        }
        if (lineageUpdated) {
            if (outputKeyLineage != null) {
                lineage.writeToFile(outputKeyLineage);
                if (verbose) {
                    System.out.println("Updated lineage saved to " + outputKeyLineage + ".");
                }
            } else {
                throw new ParameterException(
                        "The lineage was modified but an output file for the lineage was not "
                                + "specified");
            }
        }
    }

    private static SignerParams processSignerParams(OptionsParser optionsParser)
            throws OptionsParser.OptionsException, ParameterException {
        SignerParams signerParams = new SignerParams();
        String optionName;
        while ((optionName = optionsParser.nextOption()) != null) {
            if ("ks".equals(optionName)) {
                signerParams.keystoreFile = optionsParser.getRequiredValue("KeyStore file");
            } else if ("ks-key-alias".equals(optionName)) {
                signerParams.keystoreKeyAlias =
                        optionsParser.getRequiredValue("KeyStore key alias");
            } else if ("ks-pass".equals(optionName)) {
                signerParams.keystorePasswordSpec =
                        optionsParser.getRequiredValue("KeyStore password");
            } else if ("key-pass".equals(optionName)) {
                signerParams.keyPasswordSpec = optionsParser.getRequiredValue("Key password");
            } else if ("pass-encoding".equals(optionName)) {
                String charsetName =
                        optionsParser.getRequiredValue("Password character encoding");
                try {
                    signerParams.passwordCharset = PasswordRetriever.getCharsetByName(charsetName);
                } catch (IllegalArgumentException e) {
                    throw new ParameterException(
                            "Unsupported password character encoding requested using"
                                    + " --pass-encoding: " + charsetName);
                }
            } else if ("ks-type".equals(optionName)) {
                signerParams.keystoreType = optionsParser.getRequiredValue("KeyStore type");
            } else if ("ks-provider-name".equals(optionName)) {
                signerParams.keystoreProviderName =
                        optionsParser.getRequiredValue("JCA KeyStore Provider name");
            } else if ("ks-provider-class".equals(optionName)) {
                signerParams.keystoreProviderClass =
                        optionsParser.getRequiredValue("JCA KeyStore Provider class name");
            } else if ("ks-provider-arg".equals(optionName)) {
                signerParams.keystoreProviderArg =
                        optionsParser.getRequiredValue(
                                "JCA KeyStore Provider constructor argument");
            } else if ("key".equals(optionName)) {
                signerParams.keyFile = optionsParser.getRequiredValue("Private key file");
            } else if ("cert".equals(optionName)) {
                signerParams.certFile = optionsParser.getRequiredValue("Certificate file");
            } else if ("set-installed-data".equals(optionName)) {
                signerParams.signerCapabilitiesBuilder.setInstalledData(
                        optionsParser.getOptionalBooleanValue(true));
            } else if ("set-shared-uid".equals(optionName)) {
                signerParams.signerCapabilitiesBuilder.setSharedUid(
                        optionsParser.getOptionalBooleanValue(true));
            } else if ("set-permission".equals(optionName)) {
                signerParams.signerCapabilitiesBuilder.setPermission(
                        optionsParser.getOptionalBooleanValue(true));
            } else if ("set-rollback".equals(optionName)) {
                signerParams.signerCapabilitiesBuilder.setRollback(
                        optionsParser.getOptionalBooleanValue(true));
            } else if ("set-auth".equals(optionName)) {
                signerParams.signerCapabilitiesBuilder.setAuth(
                        optionsParser.getOptionalBooleanValue(true));
            } else {
                // not a signer option, reset optionsParser and let caller deal with it
                optionsParser.putOption();
                break;
            }
        }

        if (signerParams.isEmpty()) {
            throw new ParameterException("Signer specified without arguments");
        }
        return signerParams;
    }

    private static void printUsage(String page) {
        try (BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(
                                ApkSignerTool.class.getResourceAsStream(page),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + page + " resource");
        }
    }

    /**
     * Prints details from the provided certificate to stdout.
     *
     * @param cert    the certificate to be displayed.
     * @param name    the name to be used to identify the certificate.
     * @param verbose boolean indicating whether public key details from the certificate should be
     *                displayed.
     *
     * @throws NoSuchAlgorithmException     if an instance of MD5, SHA-1, or SHA-256 cannot be
     *                                      obtained.
     * @throws CertificateEncodingException if an error is encountered when encoding the
     *                                      certificate.
     */
    public static void printCertificate(X509Certificate cert, String name, boolean verbose)
            throws NoSuchAlgorithmException, CertificateEncodingException {
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }
        if (sha256 == null || sha1 == null || md5 == null) {
            sha256 = MessageDigest.getInstance("SHA-256");
            sha1 = MessageDigest.getInstance("SHA-1");
            md5 = MessageDigest.getInstance("MD5");
        }
        System.out.println(name + " certificate DN: " + cert.getSubjectDN());
        byte[] encodedCert = cert.getEncoded();
        System.out.println(name + " certificate SHA-256 digest: " + HexEncoding.encode(
                sha256.digest(encodedCert)));
        System.out.println(name + " certificate SHA-1 digest: " + HexEncoding.encode(
                sha1.digest(encodedCert)));
        System.out.println(
                name + " certificate MD5 digest: " + HexEncoding.encode(md5.digest(encodedCert)));
        if (verbose) {
            PublicKey publicKey = cert.getPublicKey();
            System.out.println(name + " key algorithm: " + publicKey.getAlgorithm());
            int keySize = -1;
            if (publicKey instanceof RSAKey) {
                keySize = ((RSAKey) publicKey).getModulus().bitLength();
            } else if (publicKey instanceof ECKey) {
                keySize = ((ECKey) publicKey).getParams()
                        .getOrder().bitLength();
            } else if (publicKey instanceof DSAKey) {
                // DSA parameters may be inherited from the certificate. We
                // don't handle this case at the moment.
                DSAParams dsaParams = ((DSAKey) publicKey).getParams();
                if (dsaParams != null) {
                    keySize = dsaParams.getP().bitLength();
                }
            }
            System.out.println(
                    name + " key size (bits): " + ((keySize != -1) ? String.valueOf(keySize)
                            : "n/a"));
            byte[] encodedKey = publicKey.getEncoded();
            System.out.println(name + " public key SHA-256 digest: " + HexEncoding.encode(
                    sha256.digest(encodedKey)));
            System.out.println(name + " public key SHA-1 digest: " + HexEncoding.encode(
                    sha1.digest(encodedKey)));
            System.out.println(
                    name + " public key MD5 digest: " + HexEncoding.encode(md5.digest(encodedKey)));
        }
    }

    /**
     * Prints the capabilities of the provided object to stdout. Each of the potential
     * capabilities is displayed along with a boolean indicating whether this object has
     * that capability.
     */
    public static void printCapabilities(SignerCapabilities capabilities) {
        System.out.println("Has installed data capability: " + capabilities.hasInstalledData());
        System.out.println("Has shared UID capability    : " + capabilities.hasSharedUid());
        System.out.println("Has permission capability    : " + capabilities.hasPermission());
        System.out.println("Has rollback capability      : " + capabilities.hasRollback());
        System.out.println("Has auth capability          : " + capabilities.hasAuth());
    }

    private static class ProviderInstallSpec {
        String className;
        String constructorParam;
        Integer position;

        private boolean isEmpty() {
            return (className == null) && (constructorParam == null) && (position == null);
        }

        private void installProvider() throws Exception {
            if (className == null) {
                throw new ParameterException(
                        "JCA Provider class name (--provider-class) must be specified");
            }

            Class<?> providerClass = Class.forName(className);
            if (!Provider.class.isAssignableFrom(providerClass)) {
                throw new ParameterException(
                        "JCA Provider class " + providerClass + " not subclass of "
                                + Provider.class.getName());
            }
            Provider provider;
            if (constructorParam != null) {
                // Single-arg Provider constructor
                provider =
                        (Provider) providerClass.getConstructor(String.class)
                                .newInstance(constructorParam);
            } else {
                // No-arg Provider constructor
                provider = (Provider) providerClass.getConstructor().newInstance();
            }

            if (position == null) {
                Security.addProvider(provider);
            } else {
                Security.insertProviderAt(provider, position);
            }
        }
    }

    /**
     * Loads the private key and certificates from either the specified keystore or files specified
     * in the signer params using the provided passwordRetriever.
     *
     * @throws ParameterException if any errors are encountered when attempting to load
     *                            the private key and certificates.
     */
    private static void loadPrivateKeyAndCerts(SignerParams params,
            PasswordRetriever passwordRetriever) throws ParameterException {
        try {
            params.loadPrivateKeyAndCerts(passwordRetriever);
            if (params.keystoreKeyAlias != null) {
                params.name = params.keystoreKeyAlias;
            } else if (params.keyFile != null) {
                String keyFileName = new File(params.keyFile).getName();
                int delimiterIndex = keyFileName.indexOf('.');
                if (delimiterIndex == -1) {
                    params.name = keyFileName;
                } else {
                    params.name = keyFileName.substring(0, delimiterIndex);
                }
            } else {
                throw new RuntimeException(
                        "Neither KeyStore key alias nor private key file available for "
                                + params.name);
            }
        } catch (ParameterException e) {
            throw new ParameterException(
                    "Failed to load signer \"" + params.name + "\":" + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            throw new ParameterException("Failed to load signer \"" + params.name + "\"");
        }
    }

    private static class SignerParams {
        String name;

        String keystoreFile;
        String keystoreKeyAlias;
        String keystorePasswordSpec;
        String keyPasswordSpec;
        Charset passwordCharset;
        String keystoreType;
        String keystoreProviderName;
        String keystoreProviderClass;
        String keystoreProviderArg;

        String keyFile;
        String certFile;

        String v1SigFileBasename;

        PrivateKey privateKey;
        List<X509Certificate> certs;
        SigningCertificateLineage lineage;
        SignerCapabilities.Builder signerCapabilitiesBuilder = new
                SignerCapabilities.Builder();

        private boolean isEmpty() {
            return (name == null)
                    && (keystoreFile == null)
                    && (keystoreKeyAlias == null)
                    && (keystorePasswordSpec == null)
                    && (keyPasswordSpec == null)
                    && (passwordCharset == null)
                    && (keystoreType == null)
                    && (keystoreProviderName == null)
                    && (keystoreProviderClass == null)
                    && (keystoreProviderArg == null)
                    && (keyFile == null)
                    && (certFile == null)
                    && (v1SigFileBasename == null)
                    && (privateKey == null)
                    && (certs == null);
        }

        private void loadPrivateKeyAndCerts(PasswordRetriever passwordRetriever) throws Exception {
            if (keystoreFile != null) {
                if (keyFile != null) {
                    throw new ParameterException(
                            "--ks and --key may not be specified at the same time");
                } else if (certFile != null) {
                    throw new ParameterException(
                            "--ks and --cert may not be specified at the same time");
                }
                loadPrivateKeyAndCertsFromKeyStore(passwordRetriever);
            } else if (keyFile != null) {
                loadPrivateKeyAndCertsFromFiles(passwordRetriever);
            } else {
                throw new ParameterException(
                        "KeyStore (--ks) or private key file (--key) must be specified");
            }
        }

        private void loadPrivateKeyAndCertsFromKeyStore(PasswordRetriever passwordRetriever)
                throws Exception {
            if (keystoreFile == null) {
                throw new ParameterException("KeyStore (--ks) must be specified");
            }

            // 1. Obtain a KeyStore implementation
            String ksType = (keystoreType != null) ? keystoreType : KeyStore.getDefaultType();
            KeyStore ks;
            if (keystoreProviderName != null) {
                // Use a named Provider (assumes the provider is already installed)
                ks = KeyStore.getInstance(ksType, keystoreProviderName);
            } else if (keystoreProviderClass != null) {
                // Use a new Provider instance (does not require the provider to be installed)
                Class<?> ksProviderClass = Class.forName(keystoreProviderClass);
                if (!Provider.class.isAssignableFrom(ksProviderClass)) {
                    throw new ParameterException(
                            "Keystore Provider class " + keystoreProviderClass + " not subclass of "
                                    + Provider.class.getName());
                }
                Provider ksProvider;
                if (keystoreProviderArg != null) {
                    // Single-arg Provider constructor
                    ksProvider =
                            (Provider) ksProviderClass.getConstructor(String.class)
                                    .newInstance(keystoreProviderArg);
                } else {
                    // No-arg Provider constructor
                    ksProvider = (Provider) ksProviderClass.getConstructor().newInstance();
                }
                ks = KeyStore.getInstance(ksType, ksProvider);
            } else {
                // Use the highest-priority Provider which offers the requested KeyStore type
                ks = KeyStore.getInstance(ksType);
            }

            // 2. Load the KeyStore
            List<char[]> keystorePasswords;
            Charset[] additionalPasswordEncodings;
            {
                String keystorePasswordSpec =
                        (this.keystorePasswordSpec != null)
                                ?  this.keystorePasswordSpec : PasswordRetriever.SPEC_STDIN;
                additionalPasswordEncodings =
                        (passwordCharset != null)
                                ? new Charset[] {passwordCharset} : new Charset[0];
                keystorePasswords =
                        passwordRetriever.getPasswords(
                                keystorePasswordSpec,
                                "Keystore password for " + name,
                                additionalPasswordEncodings);
                loadKeyStoreFromFile(
                        ks,
                        "NONE".equals(keystoreFile) ? null : keystoreFile,
                        keystorePasswords);
            }

            // 3. Load the PrivateKey and cert chain from KeyStore
            String keyAlias = null;
            PrivateKey key = null;
            try {
                if (keystoreKeyAlias == null) {
                    // Private key entry alias not specified. Find the key entry contained in this
                    // KeyStore. If the KeyStore contains multiple key entries, return an error.
                    Enumeration<String> aliases = ks.aliases();
                    if (aliases != null) {
                        while (aliases.hasMoreElements()) {
                            String entryAlias = aliases.nextElement();
                            if (ks.isKeyEntry(entryAlias)) {
                                keyAlias = entryAlias;
                                if (keystoreKeyAlias != null) {
                                    throw new ParameterException(
                                            keystoreFile + " contains multiple key entries"
                                            + ". --ks-key-alias option must be used to specify"
                                            + " which entry to use.");
                                }
                                keystoreKeyAlias = keyAlias;
                            }
                        }
                    }
                    if (keystoreKeyAlias == null) {
                        throw new ParameterException(
                                keystoreFile + " does not contain key entries");
                    }
                }

                // Private key entry alias known. Load that entry's private key.
                keyAlias = keystoreKeyAlias;
                if (!ks.isKeyEntry(keyAlias)) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a key");
                }

                Key entryKey;
                if (keyPasswordSpec != null) {
                    // Key password spec is explicitly specified. Use this spec to obtain the
                    // password and then load the key using that password.
                    List<char[]> keyPasswords =
                            passwordRetriever.getPasswords(
                                    keyPasswordSpec,
                                    "Key \"" + keyAlias + "\" password for " + name,
                                    additionalPasswordEncodings);
                    entryKey = getKeyStoreKey(ks, keyAlias, keyPasswords);
                } else {
                    // Key password spec is not specified. This means we should assume that key
                    // password is the same as the keystore password and that, if this assumption is
                    // wrong, we should prompt for key password and retry loading the key using that
                    // password.
                    try {
                        entryKey = getKeyStoreKey(ks, keyAlias, keystorePasswords);
                    } catch (UnrecoverableKeyException expected) {
                        List<char[]> keyPasswords =
                                passwordRetriever.getPasswords(
                                        PasswordRetriever.SPEC_STDIN,
                                        "Key \"" + keyAlias + "\" password for " + name,
                                        additionalPasswordEncodings);
                        entryKey = getKeyStoreKey(ks, keyAlias, keyPasswords);
                    }
                }

                if (entryKey == null) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a key");
                } else if (!(entryKey instanceof PrivateKey)) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a private"
                                    + " key. It contains a key of algorithm: "
                                    + entryKey.getAlgorithm());
                }
                key = (PrivateKey) entryKey;
            } catch (UnrecoverableKeyException e) {
                throw new IOException(
                        "Failed to obtain key with alias \"" + keyAlias + "\" from " + keystoreFile
                                + ". Wrong password?",
                        e);
            }
            this.privateKey = key;
            Certificate[] certChain = ks.getCertificateChain(keyAlias);
            if ((certChain == null) || (certChain.length == 0)) {
                throw new ParameterException(
                        keystoreFile + " entry \"" + keyAlias + "\" does not contain certificates");
            }
            this.certs = new ArrayList<>(certChain.length);
            for (Certificate cert : certChain) {
                this.certs.add((X509Certificate) cert);
            }
        }

        /**
         * Loads the password-protected keystore from storage.
         *
         * @param file file backing the keystore or {@code null} if the keystore is not file-backed,
         *        for example, a PKCS #11 KeyStore.
         */
        private static void loadKeyStoreFromFile(KeyStore ks, String file, List<char[]> passwords)
                throws Exception {
            Exception lastFailure = null;
            for (char[] password : passwords) {
                try {
                    if (file != null) {
                        try (FileInputStream in = new FileInputStream(file)) {
                            ks.load(in, password);
                        }
                    } else {
                        ks.load(null, password);
                    }
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
            if (lastFailure == null) {
                throw new RuntimeException("No keystore passwords");
            } else {
                throw lastFailure;
            }
        }

        private static Key getKeyStoreKey(KeyStore ks, String keyAlias, List<char[]> passwords)
                throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {
            UnrecoverableKeyException lastFailure = null;
            for (char[] password : passwords) {
                try {
                    return ks.getKey(keyAlias, password);
                } catch (UnrecoverableKeyException e) {
                    lastFailure = e;
                }
            }
            if (lastFailure == null) {
                throw new RuntimeException("No key passwords");
            } else {
                throw lastFailure;
            }
        }

        private void loadPrivateKeyAndCertsFromFiles(PasswordRetriever passwordRetriver)
                throws Exception {
            if (keyFile == null) {
                throw new ParameterException("Private key file (--key) must be specified");
            }
            if (certFile == null) {
                throw new ParameterException("Certificate file (--cert) must be specified");
            }
            byte[] privateKeyBlob = readFully(new File(keyFile));

            PKCS8EncodedKeySpec keySpec;
            // Potentially encrypted key blob
            try {
                EncryptedPrivateKeyInfo encryptedPrivateKeyInfo =
                        new EncryptedPrivateKeyInfo(privateKeyBlob);

                // The blob is indeed an encrypted private key blob
                String passwordSpec =
                        (keyPasswordSpec != null) ? keyPasswordSpec : PasswordRetriever.SPEC_STDIN;
                Charset[] additionalPasswordEncodings =
                        (passwordCharset != null)
                                ? new Charset[] {passwordCharset} : new Charset[0];
                List<char[]> keyPasswords =
                        passwordRetriver.getPasswords(
                                passwordSpec,
                                "Private key password for " + name,
                                additionalPasswordEncodings);
                keySpec = decryptPkcs8EncodedKey(encryptedPrivateKeyInfo, keyPasswords);
            } catch (IOException e) {
                // The blob is not an encrypted private key blob
                if (keyPasswordSpec == null) {
                    // Given that no password was specified, assume the blob is an unencrypted
                    // private key blob
                    keySpec = new PKCS8EncodedKeySpec(privateKeyBlob);
                } else {
                    throw new InvalidKeySpecException(
                            "Failed to parse encrypted private key blob " + keyFile, e);
                }
            }

            // Load the private key from its PKCS #8 encoded form.
            try {
                privateKey = loadPkcs8EncodedPrivateKey(keySpec);
            } catch (InvalidKeySpecException e) {
                throw new InvalidKeySpecException(
                        "Failed to load PKCS #8 encoded private key from " + keyFile, e);
            }

            // Load certificates
            Collection<? extends Certificate> certs;
            try (FileInputStream in = new FileInputStream(certFile)) {
                certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            List<X509Certificate> certList = new ArrayList<>(certs.size());
            for (Certificate cert : certs) {
                certList.add((X509Certificate) cert);
            }
            this.certs = certList;
        }

        private static PKCS8EncodedKeySpec decryptPkcs8EncodedKey(
                EncryptedPrivateKeyInfo encryptedPrivateKeyInfo, List<char[]> passwords)
                throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException {
            SecretKeyFactory keyFactory =
                    SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
            InvalidKeySpecException lastKeySpecException = null;
            InvalidKeyException lastKeyException = null;
            for (char[] password : passwords) {
                PBEKeySpec decryptionKeySpec = new PBEKeySpec(password);
                try {
                    SecretKey decryptionKey = keyFactory.generateSecret(decryptionKeySpec);
                    return encryptedPrivateKeyInfo.getKeySpec(decryptionKey);
                } catch (InvalidKeySpecException e) {
                    lastKeySpecException = e;
                } catch (InvalidKeyException e) {
                    lastKeyException = e;
                }
            }
            if ((lastKeyException == null) && (lastKeySpecException == null)) {
                throw new RuntimeException("No passwords");
            } else if (lastKeyException != null) {
                throw lastKeyException;
            } else {
                throw lastKeySpecException;
            }
        }

        private static PrivateKey loadPkcs8EncodedPrivateKey(PKCS8EncodedKeySpec spec)
                throws InvalidKeySpecException, NoSuchAlgorithmException {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            try {
                return KeyFactory.getInstance("DSA").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            throw new InvalidKeySpecException("Not an RSA, EC, or DSA private key");
        }
    }

    private static byte[] readFully(File file) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            drain(in, result);
        }
        return result.toByteArray();
    }

    private static void drain(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int chunkSize;
        while ((chunkSize = in.read(buf)) != -1) {
            out.write(buf, 0, chunkSize);
        }
    }

    /**
     * Indicates that there is an issue with command-line parameters provided to this tool.
     */
    private static class ParameterException extends Exception {
        private static final long serialVersionUID = 1L;

        ParameterException(String message) {
            super(message);
        }
    }
}
