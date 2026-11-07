package com.partner.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Map;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

class PqcWorkflowTest {
    @TempDir Path temp;

    @Test @DisplayName("F-01/T-01/P-01/T-02: PQC 업무 흐름, 권한, 재시작, 승인 봉투 변조")
    void pqcDocumentWorkflowPersistsAndRejectsUnauthorizedOrTamperedAccess() throws Exception {
        Path data = temp.resolve("data");
        ContractService service = service(data);
        String body = "장기 보관할 계약 본문 / confidential";
        Map<String, Object> created = service.create("alice", "bob", "PQC 계약", body);
        String id = (String)created.get("contract_id");

        assertEquals(body, service.read("alice", id, 1, false).get("content"));
        assertThrows(ApiException.class, () -> service.read("dave", id, 1, false));
        assertThrows(ApiException.class, () -> service.read("bob", id, 1, false));

        service.deliver("alice", id, 1);
        assertEquals(body, service.read("bob", id, 1, false).get("content"));
        service.comment("bob", id, 1, "조항 3의 납기일을 확인해 주세요.");
        assertEquals("조항 3의 납기일을 확인해 주세요.", service.comments("alice", id, 1).getFirst().get("content"));

        Map<String, Object> approval = service.approve("bob", id, 1);
        assertEquals(DocumentProtection.SIGNATURE_SCHEME, approval.get("scheme"));
        Map<String, Object> approved = service.read("alice", id, 1, true);
        assertEquals(true, approved.get("signature_valid"));
        assertEquals(body, approved.get("content"));
        assertEquals("ML-KEM-768", Json.str(Json.map(Json.map(Json.map(approved.get("envelope")).get("keys")).get("bob")), "scheme"));

        // Reopening the service with the same data directory must retain PQC keys and business records.
        ContractService restarted = service(data);
        assertEquals(body, restarted.read("bob", id, 1, true).get("content"));
        assertEquals(1, restarted.approvals("alice", id).size());
        assertEquals("조항 3의 납기일을 확인해 주세요.", restarted.comments("bob", id, 1).getFirst().get("content"));

        try (var db = new Storage(data.resolve("contracts.sqlite3")).begin(true)) {
            Map<String, Object> version = db.one("SELECT envelope FROM versions WHERE contract_id=? AND number=1", id);
            Map<String, Object> envelope = Json.parse((String)version.get("envelope"));
            Map<String, Object> encrypted = Json.map(envelope.get("content"));
            byte[] ciphertext = DocumentProtection.decode((String)encrypted.get("ciphertext"));
            ciphertext[ciphertext.length - 1] ^= 1;
            encrypted.put("ciphertext", DocumentProtection.encode(ciphertext));
            db.run("UPDATE versions SET envelope=? WHERE contract_id=? AND number=1", Json.text(envelope), id);
            db.commit();
        }
        assertThrows(ApiException.class, () -> service(data).read("bob", id, 1, true));
    }

    @Test @DisplayName("L-01: legacy RSA-OAEP 봉투·ECDSA 승인 호환 및 GCM 문맥 검증")
    void legacyRsaEnvelopeAndEcdsaApprovalRemainReadable() throws Exception {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        assertThrows(IllegalArgumentException.class,
            () -> Json.parse("{\"format\":\"contract-envelope-v1\",\"format\":\"contract-envelope-v2\"}"));
        assertThrows(IllegalArgumentException.class,
            () -> Json.parse("{\"format\":\"contract-envelope-v2\",\"content\":{\"scheme\":\"A\",\"scheme\":\"B\"}}"));
        CredentialStore credentials = new CredentialStore(temp.resolve("legacy-keys"), true);
        DocumentProtection protection = new DocumentProtection(credentials);
        byte[] pqcContext = Json.bytes(Json.object("domain", "contract-content-v2", "contract_id", "pqc-id", "version", 2));
        Map<String, Object> pqcEnvelope = protection.protect("PQC 본문", pqcContext, "alice", "bob");
        assertEquals("PQC 본문", protection.open("bob", pqcEnvelope, pqcContext));
        assertThrows(java.security.GeneralSecurityException.class,
            () -> protection.open("bob", pqcEnvelope, Json.bytes(Json.object("domain", "contract-content-v2", "contract_id", "other-id", "version", 2))));
        Map<String, Object> pqcContent = Json.map(pqcEnvelope.get("content"));
        byte[] pqcCiphertext = DocumentProtection.decode((String)pqcContent.get("ciphertext"));
        pqcCiphertext[pqcCiphertext.length - 1] ^= 1;
        pqcContent.put("ciphertext", DocumentProtection.encode(pqcCiphertext));
        assertThrows(java.security.GeneralSecurityException.class, () -> protection.open("bob", pqcEnvelope, pqcContext));

        byte[] context = Json.bytes(Json.object("domain", "contract-content-v1", "contract_id", "legacy-id", "version", 1));
        byte[] dataKey = new byte[32]; byte[] nonce = new byte[12]; new SecureRandom().nextBytes(dataKey); new SecureRandom().nextBytes(nonce);
        Cipher contentCipher = Cipher.getInstance("AES/GCM/NoPadding");
        contentCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, nonce));
        contentCipher.updateAAD(context);
        Map<String, Object> content = Json.object("scheme", "AES-256-GCM", "nonce", DocumentProtection.encode(nonce),
            "ciphertext", DocumentProtection.encode(contentCipher.doFinal("기존 계약 본문".getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String user : List.of("alice", "bob")) {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, credentials.key(user, "encryption", "legacy").getPublic(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            keys.put(user, Json.object("scheme", "RSA-OAEP-SHA256", "wrapped_key", DocumentProtection.encode(rsa.doFinal(dataKey))));
        }
        Map<String, Object> envelope = Json.object("format", "contract-envelope-v1", "content", content, "keys", keys);
        assertEquals("기존 계약 본문", protection.open("bob", envelope, context));

        byte[] approvalMessage = Json.bytes(Json.object("domain", "contract-approval-v1", "envelope_sha256", DocumentProtection.digest(envelope)));
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(credentials.key("bob", "approval", "legacy").getPrivate()); ecdsa.update(approvalMessage);
        String signature = Base64.getEncoder().encodeToString(ecdsa.sign());
        assertTrue(protection.verify("bob", "ECDSA-P256-SHA256", approvalMessage, signature));
        assertFalse(protection.verify("bob", DocumentProtection.SIGNATURE_SCHEME, approvalMessage, signature));
    }

    @Test @DisplayName("P-02: PQC 문서는 유효한 레거시 ECDSA 승인으로 대체할 수 없음")
    void validLegacyEcdsaCannotApprovePqcEnvelope() throws Exception {
        Path data = temp.resolve("signature-scheme-binding");
        ContractService service = service(data);
        Map<String, Object> created = service.create("alice", "bob", "서명 방식 결합", "PQC 문서 본문");
        String id = (String)created.get("contract_id");
        service.deliver("alice", id, 1);
        service.approve("bob", id, 1);
        Map<String, Object> approved = service.read("alice", id, 1, true);
        Map<String, Object> envelope = Json.map(approved.get("envelope"));
        Map<String, Object> payload = Json.map(approved.get("signed_payload"));
        Map<String, Object> oldApproval;
        try (var db = new Storage(data.resolve("contracts.sqlite3")).begin(false)) {
            oldApproval = db.one("SELECT * FROM approvals WHERE contract_id=? AND version=1", id);
        }

        Map<String, Object> legacyMessage = Json.object("domain", "contract-approval-v1", "context", payload.get("context"),
            "envelope_sha256", DocumentProtection.digest(envelope), "approval_id", oldApproval.get("id"),
            "approver", "bob", "approved_at", oldApproval.get("created_at"), "signature_scheme", "ECDSA-P256-SHA256");
        CredentialStore credentials = new CredentialStore(data.resolve("keys"), false, false);
        byte[] message = Json.bytes(legacyMessage);
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(credentials.key("bob", "approval", "legacy").getPrivate()); ecdsa.update(message);
        String forgedClassicalSignature = Base64.getEncoder().encodeToString(ecdsa.sign());
        assertTrue(new DocumentProtection(credentials).verify("bob", "ECDSA-P256-SHA256", message, forgedClassicalSignature));

        try (var db = new Storage(data.resolve("contracts.sqlite3")).begin(true)) {
            db.run("UPDATE approvals SET scheme=?, signature=? WHERE contract_id=? AND version=1",
                "ECDSA-P256-SHA256", forgedClassicalSignature, id);
            db.commit();
        }
        assertThrows(ApiException.class, () -> service.read("alice", id, 1, true));
    }

    @Test @DisplayName("U-01: 기존 DB와 RSA/EC 키만 있는 최초 PQC 업그레이드")
    void legacyOnlyDatabaseCanStartAndTransitionToPqc() throws Exception {
        Path data = temp.resolve("legacy-upgrade");
        Path keys = data.resolve("keys"); java.nio.file.Files.createDirectories(keys);
        var legacyPairs = new java.util.HashMap<String, java.security.KeyPair>();
        for (String user : List.of("alice", "bob", "carol", "dave")) {
            var encryption = KeyPairGenerator.getInstance("RSA"); encryption.initialize(2048);
            var signing = KeyPairGenerator.getInstance("EC"); signing.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            legacyPairs.put(user + ".encryption", encryption.generateKeyPair());
            legacyPairs.put(user + ".approval", signing.generateKeyPair());
            java.nio.file.Files.writeString(keys.resolve(user + ".encryption.pem"), CredentialStore.pem("PRIVATE KEY", legacyPairs.get(user + ".encryption").getPrivate().getEncoded()));
            java.nio.file.Files.writeString(keys.resolve(user + ".approval.pem"), CredentialStore.pem("PRIVATE KEY", legacyPairs.get(user + ".approval").getPrivate().getEncoded()));
        }
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        String contractId = "legacy-upgrade-contract";
        String createdAt = "2026-01-01T00:00:00Z";
        String title = "기존 계약";
        byte[] context = Json.bytes(Json.object("domain", "contract-content-v1", "contract_id", contractId,
            "version", 1, "author", "alice", "recipient", "bob", "title", title, "created_at", createdAt));
        Map<String, Object> envelope = legacyEnvelope(legacyPairs, context, "전환 전 기존 본문");
        Map<String, Object> legacyApprovalMessage = Json.object("domain", "contract-approval-v1", "context", Json.parse(new String(context, StandardCharsets.UTF_8)),
            "envelope_sha256", DocumentProtection.digest(envelope), "approval_id", "legacy-approval", "approver", "bob",
            "approved_at", createdAt, "signature_scheme", "ECDSA-P256-SHA256");
        Signature legacySignature = Signature.getInstance("SHA256withECDSA");
        legacySignature.initSign(legacyPairs.get("bob.approval").getPrivate()); legacySignature.update(Json.bytes(legacyApprovalMessage));
        String encodedLegacySignature = DocumentProtection.encode(legacySignature.sign());
        var oldStorage = new Storage(data.resolve("contracts.sqlite3"));
        try (var db = oldStorage.begin(true)) {
            db.run("INSERT INTO metadata(key,value) VALUES ('initialized','1')");
            db.run("INSERT INTO contracts VALUES (?, ?, ?, 1, ?)", contractId, "alice", "bob", createdAt);
            db.run("INSERT INTO versions VALUES (?, 1, ?, ?, ?)", contractId, title, createdAt, Json.text(envelope));
            db.run("INSERT INTO deliveries VALUES (?, ?, 1, ?)", "legacy-delivery", contractId, createdAt);
            db.run("INSERT INTO approvals VALUES (?, ?, 1, ?, ?, ?, ?)", "legacy-approval", contractId, "bob", createdAt,
                "ECDSA-P256-SHA256", encodedLegacySignature);
            db.commit();
        }

        String newId;
        try (ConfigurableApplicationContext contextAfterUpgrade = boot(data)) {
            ContractService upgraded = contextAfterUpgrade.getBean(ContractService.class);
            assertEquals("전환 전 기존 본문", upgraded.read("bob", contractId, 1, false).get("content"));
            assertEquals(true, upgraded.read("bob", contractId, 1, true).get("signature_valid"));
            assertTrue(new Storage(data.resolve("contracts.sqlite3")).pqcKeysInitialized());
            int port = ((ServletWebServerApplicationContext)contextAfterUpgrade).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            var createdResponse = api(client, base, "POST", "/contracts", "alice",
                Json.text(Json.object("recipient", "bob", "title", "전환 후 계약", "content", "신규 PQC 본문")));
            assertEquals(201, createdResponse.statusCode());
            newId = Json.str(Json.parse(createdResponse.body()), "contract_id");
            assertEquals(201, api(client, base, "POST", "/contracts/" + newId + "/deliveries", "alice",
                Json.text(Json.object("version", 1))).statusCode());
            assertEquals("신규 PQC 본문", Json.str(Json.parse(api(client, base, "GET", "/contracts/" + newId, "bob", null).body()), "content"));
            assertEquals(201, api(client, base, "POST", "/contracts/" + newId + "/approvals", "bob",
                Json.text(Json.object("version", 1))).statusCode());
            Map<String, Object> approved = Json.parse(api(client, base, "GET", "/contracts/" + newId + "/approved-copy", "alice", null).body());
            assertEquals(true, approved.get("signature_valid"));
            assertEquals("신규 PQC 본문", approved.get("content"));
        }
        try (ConfigurableApplicationContext restarted = boot(data)) {
            int port = ((ServletWebServerApplicationContext)restarted).getWebServer().getPort();
            Map<String, Object> approvedAgain = Json.parse(api(HttpClient.newHttpClient(), "http://127.0.0.1:" + port,
                "GET", "/contracts/" + newId + "/approved-copy", "alice", null).body());
            assertEquals(true, approvedAgain.get("signature_valid"));
            assertEquals("신규 PQC 본문", approvedAgain.get("content"));
        }

        // The persistent marker changes the next app-context startup policy: key loss now fails closed.
        Path privateKey = keys.resolve("bob.encryption.pqc.pem");
        java.nio.file.Files.delete(privateKey);
        Exception startupFailure = assertThrows(Exception.class, () -> boot(data));
        assertTrue(hasCause(startupFailure, java.security.InvalidKeyException.class));
        assertFalse(java.nio.file.Files.exists(privateKey));
    }

    @Test @DisplayName("K-02: 저장 데이터가 있는 상태의 PQC 개인키 유실은 자동 재생성하지 않음")
    void missingPersistedKeyFailsClosed() throws Exception {
        Path data = temp.resolve("key-loss-data");
        service(data).create("alice", "bob", "키 유실 시험", "기존 문서");
        Path privateKey = data.resolve("keys/bob.encryption.pqc.pem");
        java.nio.file.Files.delete(privateKey);
        Exception startupFailure = assertThrows(Exception.class, () -> boot(data));
        assertTrue(hasCause(startupFailure, java.security.InvalidKeyException.class));
        assertFalse(java.nio.file.Files.exists(privateKey));
    }

    @Test @DisplayName("K-02b: 저장된 공개키·개인키 불일치는 fail-closed")
    void mismatchedPersistedKeyPairFailsClosed() throws Exception {
        Path data = temp.resolve("key-mismatch-data");
        service(data).create("alice", "bob", "키쌍 일치 시험", "기존 문서");
        Path keys = data.resolve("keys");
        java.nio.file.Files.copy(keys.resolve("alice.encryption.pqc.pub.pem"),
            keys.resolve("bob.encryption.pqc.pub.pem"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Exception startupFailure = assertThrows(Exception.class, () -> boot(data));
        assertTrue(hasCause(startupFailure, java.security.InvalidKeyException.class));
    }

    private ContractService service(Path data) throws Exception {
        return Application.openService(data, false);
    }

    private ConfigurableApplicationContext boot(Path data) {
        return new SpringApplicationBuilder(Application.class)
            .web(WebApplicationType.SERVLET)
            .run("--workspace.directory=" + data.toAbsolutePath(), "--workspace.seed=false",
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR");
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (type.isInstance(current)) return true;
        return false;
    }

    private HttpResponse<String> api(HttpClient client, String base, String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).header("X-User", user);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> legacyEnvelope(Map<String, java.security.KeyPair> credentials, byte[] context, String text) throws Exception {
        byte[] dataKey = new byte[32]; byte[] nonce = new byte[12]; new SecureRandom().nextBytes(dataKey); new SecureRandom().nextBytes(nonce);
        Cipher contentCipher = Cipher.getInstance("AES/GCM/NoPadding");
        contentCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, nonce));
        contentCipher.updateAAD(context);
        Map<String, Object> encrypted = Json.object("scheme", "AES-256-GCM", "nonce", DocumentProtection.encode(nonce),
            "ciphertext", DocumentProtection.encode(contentCipher.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> recipientKeys = new LinkedHashMap<>();
        for (String user : List.of("alice", "bob")) {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, credentials.get(user + ".encryption").getPublic(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            recipientKeys.put(user, Json.object("scheme", "RSA-OAEP-SHA256", "wrapped_key", DocumentProtection.encode(rsa.doFinal(dataKey))));
        }
        return Json.object("format", "contract-envelope-v1", "content", encrypted, "keys", recipientKeys);
    }
}
