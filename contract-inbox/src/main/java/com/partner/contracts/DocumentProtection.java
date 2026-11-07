package com.partner.contracts;

import org.bouncycastle.jcajce.spec.KTSParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.SecretKey;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import static com.partner.contracts.Json.*;

final class DocumentProtection {
    static final String ENVELOPE_V2 = "contract-envelope-v2";
    static final String KEM_SCHEME = "ML-KEM-768";
    static final String SIGNATURE_SCHEME = "ML-DSA-65";
    private final CredentialStore credentials;
    private final SecureRandom random = new SecureRandom();
    DocumentProtection(CredentialStore credentials) { this.credentials = credentials; }
    String signatureScheme() { return SIGNATURE_SCHEME; }

    private Cipher legacyTransport(int mode, String identity) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        var key = credentials.key(identity, "encryption", "legacy");
        cipher.init(mode, mode == Cipher.ENCRYPT_MODE ? key.getPublic() : key.getPrivate(),
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher;
    }

    Map<String, Object> protect(String content, byte[] context, String author, String recipient) throws GeneralSecurityException {
        byte[] dataKey = new byte[32]; byte[] contentNonce = new byte[12];
        random.nextBytes(dataKey); random.nextBytes(contentNonce);
        Cipher contentCipher = Cipher.getInstance("AES/GCM/NoPadding");
        contentCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, contentNonce));
        contentCipher.updateAAD(context);
        var encrypted = object("scheme", "AES-256-GCM", "nonce", encode(contentNonce),
            "ciphertext", encode(contentCipher.doFinal(content.getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> recipients = new LinkedHashMap<>();
        for (String identity : List.of(author, recipient)) {
            KEM kem = KEM.getInstance("ML-KEM", "BC");
            var key = credentials.key(identity, "encryption", "pqc");
            var parameters = new KTSParameterSpec.Builder("AES", 256).build();
            KEM.Encapsulated encapsulated = kem.newEncapsulator(key.getPublic(), parameters, random).encapsulate();
            SecretKey kek = encapsulated.key();
            byte[] wrapNonce = new byte[12]; random.nextBytes(wrapNonce);
            Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
            wrap.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(128, wrapNonce));
            wrap.updateAAD(wrapAad(identity, context));
            recipients.put(identity, object("scheme", KEM_SCHEME, "encapsulation", encode(encapsulated.encapsulation()),
                "nonce", encode(wrapNonce), "wrapped_key", encode(wrap.doFinal(dataKey))));
        }
        Arrays.fill(dataKey, (byte)0);
        return object("format", ENVELOPE_V2, "content", encrypted, "keys", recipients);
    }

    String open(String identity, Map<String, Object> envelope, byte[] context) throws GeneralSecurityException {
        String format = str(envelope, "format");
        var wrapped = map(map(envelope.get("keys")).get(identity));
        var encrypted = map(envelope.get("content"));
        byte[] dataKey;
        if ("contract-envelope-v1".equals(format)) {
            if (!"RSA-OAEP-SHA256".equals(wrapped.get("scheme")) || !"AES-256-GCM".equals(encrypted.get("scheme")))
                throw new GeneralSecurityException("Unsupported legacy document format");
            dataKey = legacyTransport(Cipher.DECRYPT_MODE, identity).doFinal(decode(str(wrapped, "wrapped_key")));
        } else if (ENVELOPE_V2.equals(format)) {
            if (!KEM_SCHEME.equals(wrapped.get("scheme")) || !"AES-256-GCM".equals(encrypted.get("scheme")))
                throw new GeneralSecurityException("Unsupported PQC document format");
            KEM kem = KEM.getInstance("ML-KEM", "BC");
            var key = credentials.key(identity, "encryption", "pqc");
            var parameters = new KTSParameterSpec.Builder("AES", 256).build();
            SecretKey kek = kem.newDecapsulator(key.getPrivate(), parameters).decapsulate(decode(str(wrapped, "encapsulation")));
            Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
            unwrap.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, decode(str(wrapped, "nonce"))));
            unwrap.updateAAD(wrapAad(identity, context));
            dataKey = unwrap.doFinal(decode(str(wrapped, "wrapped_key")));
        } else throw new GeneralSecurityException("Unsupported document format");

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, decode(str(encrypted, "nonce"))));
            cipher.updateAAD(context);
            return new String(cipher.doFinal(decode(str(encrypted, "ciphertext"))), StandardCharsets.UTF_8);
        } finally { Arrays.fill(dataKey, (byte)0); }
    }

    private static byte[] wrapAad(String identity, byte[] context) {
        byte[] name = identity.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte["contract-key-wrap-v2".length() + 1 + name.length + 1 + context.length];
        byte[] domain = "contract-key-wrap-v2".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(domain, 0, result, 0, domain.length); result[domain.length] = 0;
        System.arraycopy(name, 0, result, domain.length + 1, name.length); result[domain.length + 1 + name.length] = 0;
        System.arraycopy(context, 0, result, domain.length + 2 + name.length, context.length);
        return result;
    }

    String sign(String identity, byte[] message) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("MLDSA", "BC");
        signature.initSign(credentials.key(identity, "approval", "pqc").getPrivate(), random); signature.update(message);
        return encode(signature.sign());
    }
    boolean verify(String identity, String scheme, byte[] message, String encoded) {
        try {
            if (SIGNATURE_SCHEME.equals(scheme)) {
                Signature signature = Signature.getInstance("MLDSA", "BC");
                signature.initVerify(credentials.key(identity, "approval", "pqc").getPublic()); signature.update(message);
                return signature.verify(decode(encoded));
            }
            if ("ECDSA-P256-SHA256".equals(scheme)) {
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initVerify(credentials.key(identity, "approval", "legacy").getPublic()); signature.update(message);
                return signature.verify(decode(encoded));
            }
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
    }
    Map<String, Object> publicKey(String identity, String purpose) {
        String scheme = purpose.equals("encryption") ? KEM_SCHEME : SIGNATURE_SCHEME;
        return publicKey(identity, purpose, scheme);
    }
    Map<String, Object> publicKey(String identity, String purpose, String scheme) {
        boolean legacy = purpose.equals("encryption") ? "RSA-OAEP-SHA256".equals(scheme) : "ECDSA-P256-SHA256".equals(scheme);
        KeyPair pair = credentials.key(identity, purpose, legacy ? "legacy" : "pqc");
        String actual = legacy ? scheme : purpose.equals("encryption") ? KEM_SCHEME : SIGNATURE_SCHEME;
        return object("scheme", actual, "encoding", "pem", "key", CredentialStore.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }
    static String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String digest512(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes(value))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    static byte[] decode(String value) { return Base64.getDecoder().decode(value); }
}
