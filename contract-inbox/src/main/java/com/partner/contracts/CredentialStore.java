package com.partner.contracts;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import javax.crypto.KEM;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

final class CredentialStore {
    private final Map<String, KeyPair> keys = new HashMap<>();
    CredentialStore(Path directory, boolean allowGeneration) throws Exception {
        this(directory, allowGeneration, allowGeneration);
    }
    CredentialStore(Path directory, boolean allowLegacyGeneration, boolean allowPqcGeneration) throws Exception {
        Files.createDirectories(directory); permissions(directory, true);
        for (var user : Directory.USERS) {
            String identity = (String)user.get("id");
            loadLegacy(directory, identity, "encryption", allowLegacyGeneration);
            loadLegacy(directory, identity, "approval", allowLegacyGeneration);
            loadPqc(directory, identity, "encryption", allowPqcGeneration);
            loadPqc(directory, identity, "approval", allowPqcGeneration);
        }
    }

    private void loadLegacy(Path directory, String identity, String purpose, boolean allowGeneration) throws Exception {
        String name = identity + "." + purpose;
        Path path = directory.resolve(name + ".pem");
        boolean encryption = purpose.equals("encryption");
        if (!Files.exists(path)) {
            if (!allowGeneration) throw new InvalidKeyException("Stored credential is missing: " + path.getFileName());
            KeyPairGenerator generator = KeyPairGenerator.getInstance(encryption ? "RSA" : "EC");
            if (encryption) generator.initialize(2048);
            else generator.initialize(new ECGenParameterSpec("secp256r1"));
            writePrivate(path, generator.generateKeyPair().getPrivate().getEncoded());
        }
        permissions(path, false);
        PrivateKey privateKey;
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(path)))) {
            privateKey = new JcaPEMKeyConverter().setProvider("BC").getPrivateKey((PrivateKeyInfo) parser.readObject());
        }
        PublicKey publicKey;
        if (encryption) {
            var rsa = (RSAPrivateCrtKey) privateKey;
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        } else {
            var ec = (ECPrivateKey) privateKey;
            var point = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256r1").getG().multiply(ec.getS()).normalize();
            var spec = new ECPublicKeySpec(new ECPoint(point.getAffineXCoord().toBigInteger(), point.getAffineYCoord().toBigInteger()), ec.getParams());
            publicKey = KeyFactory.getInstance("EC").generatePublic(spec);
        }
        keys.put(name + ".legacy", new KeyPair(publicKey, privateKey));
    }

    private void loadPqc(Path directory, String identity, String purpose, boolean allowGeneration) throws Exception {
        String name = identity + "." + purpose + ".pqc";
        Path privatePath = directory.resolve(name + ".pem");
        Path publicPath = directory.resolve(name + ".pub.pem");
        if (!Files.exists(privatePath) || !Files.exists(publicPath)) {
            if (!allowGeneration) throw new InvalidKeyException("Stored credential is incomplete: " + name);
            KeyPair pair = generatePqc(purpose);
            writePqcPair(privatePath, publicPath, pair);
            keys.put(name, pair);
            return;
        }
        permissions(privatePath, false); permissions(publicPath, false);
        PrivateKey privateKey;
        PublicKey publicKey;
        var converter = new JcaPEMKeyConverter().setProvider("BC");
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(privatePath)))) {
            privateKey = converter.getPrivateKey((PrivateKeyInfo) parser.readObject());
        }
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(publicPath)))) {
            publicKey = converter.getPublicKey((SubjectPublicKeyInfo) parser.readObject());
        }
        KeyPair pair = new KeyPair(publicKey, privateKey);
        if (!pqcPairMatches(pair, purpose)) {
            if (!allowGeneration) throw new InvalidKeyException("Stored public/private key pair does not match: " + name);
            pair = generatePqc(purpose);
            writePqcPair(privatePath, publicPath, pair);
        }
        keys.put(name, pair);
    }

    private static KeyPair generatePqc(String purpose) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(purpose.equals("encryption") ? "ML-KEM" : "MLDSA", "BC");
        generator.initialize(purpose.equals("encryption") ? MLKEMParameterSpec.ml_kem_768 : MLDSAParameterSpec.ml_dsa_65, new SecureRandom());
        return generator.generateKeyPair();
    }
    private static void writePqcPair(Path privatePath, Path publicPath, KeyPair pair) throws Exception {
        writePublic(publicPath, pair.getPublic().getEncoded());
        writePrivate(privatePath, pair.getPrivate().getEncoded());
    }
    private static boolean pqcPairMatches(KeyPair pair, String purpose) throws Exception {
        byte[] challenge = "contract-key-pair-self-test-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (purpose.equals("approval")) {
            Signature signature = Signature.getInstance("MLDSA", "BC");
            signature.initSign(pair.getPrivate(), new SecureRandom()); signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(pair.getPublic()); signature.update(challenge);
            return signature.verify(signed);
        }
        KEM kem = KEM.getInstance("ML-KEM", "BC");
        var parameters = new org.bouncycastle.jcajce.spec.KTSParameterSpec.Builder("AES", 256).build();
        var encapsulated = kem.newEncapsulator(pair.getPublic(), parameters, new SecureRandom()).encapsulate();
        var recovered = kem.newDecapsulator(pair.getPrivate(), parameters).decapsulate(encapsulated.encapsulation());
        return MessageDigest.isEqual(encapsulated.key().getEncoded(), recovered.getEncoded());
    }

    private static void writePrivate(Path path, byte[] encoded) throws Exception {
        writeAtomically(path, pem("PRIVATE KEY", encoded));
    }
    private static void writePublic(Path path, byte[] encoded) throws Exception {
        writeAtomically(path, pem("PUBLIC KEY", encoded));
    }
    private static void writeAtomically(Path path, String text) throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), ".credential-", ".tmp");
        try {
            permissions(temporary, false);
            Files.writeString(temporary, text);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    KeyPair key(String identity, String purpose) { return key(identity, purpose, "pqc"); }
    KeyPair key(String identity, String purpose, String family) {
        String suffix = family.equals("legacy") ? ".legacy" : ".pqc";
        KeyPair result = keys.get(identity + "." + purpose + suffix);
        if (result == null) throw new IllegalArgumentException("Unknown credential");
        return result;
    }
    static String pem(String label, byte[] data) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(data) + "\n-----END " + label + "-----\n";
    }
    static void permissions(Path path, boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
}
