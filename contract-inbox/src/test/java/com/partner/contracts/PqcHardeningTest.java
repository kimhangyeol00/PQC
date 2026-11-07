package com.partner.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.security.Signature;
import java.util.Map;
import static com.partner.contracts.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class PqcHardeningTest {
    @TempDir Path temp;

    @Test
    void encryptedCommentCannotFallBackToPlaintextAfterTampering() throws Exception {
        ContractService service = Application.openService(temp, false);
        String id = str(service.create("alice", "bob", "의견 시험", "본문"), "contract_id");
        service.deliver("alice", id, 1);
        String commentId = str(service.comment("bob", id, 1, "비밀 의견"), "id");
        for (String replacement : new String[] {"injected plaintext", "{\"format\":\"unknown\"}", "{"}) {
            try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
                db.run("UPDATE comments SET content=? WHERE id=?", replacement, commentId); db.commit();
            }
            ApiException error = assertThrows(ApiException.class, () -> service.comments("alice", id, 1));
            assertEquals(409, error.status);
        }
    }

    @Test
    void legacyPlaintextStartingWithBraceRemainsReadable() throws Exception {
        ContractService service = Application.openService(temp, false);
        String id = str(service.create("alice", "bob", "기존 의견", "본문"), "contract_id");
        service.deliver("alice", id, 1);
        String original = "{검토 의견: JSON이 아닌 기존 평문";
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            db.run("INSERT INTO comments(id,contract_id,version,author,content,created_at) VALUES (?,?,1,?,?,?)",
                "legacy-comment", id, "bob", original, ContractService.now()); db.commit();
        }
        assertEquals(original, service.comments("alice", id, 1).getFirst().get("content"));
    }

    @Test
    void validLegacySignatureCannotApprovePqcEnvelope() throws Exception {
        ContractService service = Application.openService(temp, false);
        String id = str(service.create("alice", "bob", "서명 조합", "본문"), "contract_id");
        service.deliver("alice", id, 1); service.approve("bob", id, 1);
        Map<String, Object> approved = service.read("alice", id, 1, true);
        Map<String, Object> message = map(approved.get("signed_payload"));
        message.remove("envelope_sha512");
        message.put("envelope_sha256", DocumentProtection.digest(approved.get("envelope")));
        message.put("signature_scheme", "ECDSA-P256-SHA256");
        CredentialStore credentials = new CredentialStore(temp.resolve("keys"), false);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(credentials.key("bob", "approval", "legacy").getPrivate());
        signature.update(bytes(message));
        String encoded = DocumentProtection.encode(signature.sign());
        assertTrue(new DocumentProtection(credentials).verify("bob", "ECDSA-P256-SHA256", bytes(message), encoded));
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            db.run("UPDATE approvals SET scheme=?,signature=? WHERE contract_id=?", "ECDSA-P256-SHA256", encoded, id);
            db.commit();
        }
        assertEquals(409, assertThrows(ApiException.class, () -> service.read("alice", id, 1, true)).status);
    }

    @Test
    void storedJsonRejectsDuplicateKeysAndTrailingDocuments() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"format\":\"v1\",\"format\":\"v2\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"format\":\"v2\"} {}"));
    }

    @Test
    void oldMixedCommentSchemaMigratesOnceAndPreservesApiShape() throws Exception {
        ContractService service = Application.openService(temp, false);
        String id = str(service.create("alice", "bob", "이전 DB", "본문"), "contract_id");
        service.deliver("alice", id, 1);
        String encryptedId = str(service.comment("bob", id, 1, "기존 PQC 의견"), "id");
        String plaintext = "{이전 자유 형식 의견";
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            db.run("INSERT INTO comments(id,contract_id,version,author,content,created_at) VALUES (?,?,1,?,?,?)",
                "old-plain", id, "bob", plaintext, "2020-01-01T00:00:00Z");
            db.run("ALTER TABLE comments DROP COLUMN protection_format"); db.commit();
        }
        ContractService migrated = Application.openService(temp, false);
        var comments = migrated.comments("alice", id, 1);
        assertEquals(plaintext, comments.getFirst().get("content"));
        assertEquals("기존 PQC 의견", comments.getLast().get("content"));
        assertTrue(comments.stream().noneMatch(row -> row.containsKey("protection_format")));
        assertEquals(comments, Application.openService(temp, false).comments("alice", id, 1));
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            assertEquals(DocumentProtection.ENVELOPE_V2,
                db.one("SELECT protection_format FROM comments WHERE id=?", encryptedId).get("protection_format"));
            db.run("UPDATE comments SET content=? WHERE id=?", "tampered after migration", encryptedId); db.commit();
        }
        ContractService restarted = Application.openService(temp, false);
        assertEquals(409, assertThrows(ApiException.class, () -> restarted.comments("bob", id, 1)).status);
    }

    @Test
    void commentCiphertextAndUnknownStorageFormatAreRejected() throws Exception {
        ContractService service = Application.openService(temp, false);
        String id = str(service.create("alice", "bob", "변조 시험", "본문"), "contract_id");
        service.deliver("alice", id, 1);
        String commentId = str(service.comment("bob", id, 1, "암호화 의견"), "id");
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            var envelope = parse(str(db.one("SELECT content FROM comments WHERE id=?", commentId), "content"));
            var encrypted = map(envelope.get("content"));
            byte[] value = DocumentProtection.decode(str(encrypted, "ciphertext")); value[0] ^= 1;
            encrypted.put("ciphertext", DocumentProtection.encode(value));
            db.run("UPDATE comments SET content=? WHERE id=?", text(envelope), commentId); db.commit();
        }
        assertEquals(409, assertThrows(ApiException.class, () -> service.comments("bob", id, 1)).status);
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            db.run("UPDATE comments SET protection_format='unknown' WHERE id=?", commentId); db.commit();
        }
        assertEquals(409, assertThrows(ApiException.class, () -> service.comments("alice", id, 1)).status);
    }

    @Test
    void kemEncapsulationAndWrappedKeyTamperingFailClosed() throws Exception {
        Application.openService(temp, false);
        var protection = new DocumentProtection(new CredentialStore(temp.resolve("keys"), false));
        byte[] context = bytes(object("contract_id", "test", "version", 1));
        var original = protection.protect("secret", context, "alice", "bob");
        for (String field : new String[] {"encapsulation", "wrapped_key", "nonce"}) {
            var envelope = parse(text(original));
            var recipient = map(map(envelope.get("keys")).get("bob"));
            byte[] value = DocumentProtection.decode(str(recipient, field)); value[0] ^= 1;
            recipient.put(field, DocumentProtection.encode(value));
            assertThrows(java.security.GeneralSecurityException.class, () -> protection.open("bob", envelope, context), field);
        }
        assertEquals("secret", protection.open("bob", original, context));
    }

    @Test
    void missingBothPqcKeyFilesCannotRegenerateEvenWithoutInitializationMarker() throws Exception {
        ContractService service = Application.openService(temp, false);
        service.create("alice", "bob", "보존", "기존 자료");
        try (var db = new Storage(temp.resolve("contracts.sqlite3")).begin(true)) {
            db.run("DELETE FROM metadata WHERE key='pqc_keys_initialized'"); db.commit();
        }
        Path privateKey = temp.resolve("keys/bob.encryption.pqc.pem");
        Path publicKey = temp.resolve("keys/bob.encryption.pqc.pub.pem");
        java.nio.file.Files.delete(privateKey); java.nio.file.Files.delete(publicKey);
        assertThrows(java.security.InvalidKeyException.class, () -> Application.openService(temp, false));
        assertFalse(java.nio.file.Files.exists(privateKey)); assertFalse(java.nio.file.Files.exists(publicKey));
    }
}
