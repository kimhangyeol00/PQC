package com.partner.contracts;

import java.time.Instant;
import java.util.*;
import static com.partner.contracts.Json.*;

final class ContractService {
    private final Storage storage;
    private final DocumentProtection protection;
    ContractService(Storage storage, DocumentProtection protection) { this.storage = storage; this.protection = protection; }
    static String now() { return Instant.now().toString(); }
    static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private Map<String, Object> contract(Storage.Tx db, String id, String actor) {
        var row = db.one("SELECT * FROM contracts WHERE id=?", id);
        if (row == null || (!actor.equals(row.get("author")) && !actor.equals(row.get("recipient"))))
            throw new ApiException(404, "계약을 찾을 수 없습니다.");
        return row;
    }
    private Map<String, Object> version(Storage.Tx db, Map<String, Object> contract, Object number, String actor) {
        var row = db.one("SELECT * FROM versions WHERE contract_id=? AND number=?", contract.get("id"), number);
        if (row == null || (actor.equals(contract.get("recipient")) && delivery(db, contract.get("id"), number) == null))
            throw new ApiException(404, "문서 버전을 찾을 수 없습니다.");
        return row;
    }
    private Map<String, Object> delivery(Storage.Tx db, Object id, Object number) {
        return db.one("SELECT * FROM deliveries WHERE contract_id=? AND version=?", id, number);
    }
    private Map<String, Object> context(Map<String, Object> contract, Map<String, Object> version) {
        boolean pqc = !version.containsKey("envelope") || DocumentProtection.ENVELOPE_V2.equals(parse(str(version, "envelope")).get("format"));
        return object("domain", pqc ? "contract-content-v2" : "contract-content-v1", "contract_id", contract.get("id"), "version", version.get("number"),
            "author", contract.get("author"), "recipient", contract.get("recipient"), "title", version.get("title"), "created_at", version.get("created_at"));
    }
    private Map<String, Object> message(Map<String, Object> contract, Map<String, Object> version, Map<String, Object> approval) {
        boolean pqc = DocumentProtection.SIGNATURE_SCHEME.equals(approval.get("scheme"));
        String fingerprintName = pqc ? "envelope_sha512" : "envelope_sha256";
        String fingerprint = pqc ? DocumentProtection.digest512(parse(str(version, "envelope")))
            : DocumentProtection.digest(parse(str(version, "envelope")));
        return object("domain", "contract-approval-v1", "context", context(contract, version),
            fingerprintName, fingerprint, "approval_id", approval.get("id"), "approver", approval.get("approver"),
            "approved_at", approval.get("created_at"), "signature_scheme", approval.get("scheme"));
    }
    private Map<String, Object> verified(Storage.Tx db, Map<String, Object> contract, Map<String, Object> version) {
        var approval = db.one("SELECT * FROM approvals WHERE contract_id=? AND version=?", contract.get("id"), version.get("number"));
        if (approval == null) return null;
        boolean valid;
        try {
            valid = contract.get("recipient").equals(approval.get("approver"))
                && protection.verify(str(approval, "approver"), str(approval, "scheme"), bytes(message(contract, version, approval)), str(approval, "signature"));
        } catch (RuntimeException e) { valid = false; }
        if (!valid) throw new ApiException(409, "승인 정보의 무결성을 확인할 수 없습니다.");
        return approval;
    }
    private String content(Map<String, Object> contract, Map<String, Object> version, String actor) {
        try { return protection.open(actor, parse(str(version, "envelope")), bytes(context(contract, version))); }
        catch (Exception e) { throw new ApiException(409, "문서의 무결성을 확인할 수 없습니다."); }
    }
    private Map<String, Object> insert(Storage.Tx db, Map<String, Object> contract, int number, String title, String content) {
        var version = object("number", number, "title", title, "created_at", now());
        try {
            var envelope = protection.protect(content, bytes(context(contract, version)), str(contract, "author"), str(contract, "recipient"));
            db.run("INSERT INTO versions VALUES (?, ?, ?, ?, ?)", contract.get("id"), number, title, version.get("created_at"), text(envelope));
            db.run("UPDATE contracts SET latest_version=? WHERE id=?", number, contract.get("id"));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Document creation failed", e); }
        return object("contract_id", contract.get("id"), "version", number, "title", title, "status", "draft");
    }
    Map<String, Object> create(String actor, String recipient, String title, String content) {
        if (!Directory.user(actor).get("role").equals("author")) throw new ApiException(403, "작성자만 계약을 만들 수 있습니다.");
        if (!Directory.user(recipient).get("role").equals("reviewer")) throw new ApiException(422, "검토자를 수신자로 지정해 주세요.");
        try (var db = storage.begin(true)) {
            var contract = object("id", id(), "author", actor, "recipient", recipient, "created_at", now());
            db.run("INSERT INTO contracts VALUES (?, ?, ?, 1, ?)", contract.get("id"), actor, recipient, contract.get("created_at"));
            var result = insert(db, contract, 1, title, content); db.commit(); return result;
        }
    }
    Map<String, Object> createVersion(String actor, String id, String title, String content) {
        try (var db = storage.begin(true)) {
            var contract = contract(db, id, actor);
            if (!actor.equals(contract.get("author"))) throw new ApiException(403, "계약 작성자만 새 버전을 만들 수 있습니다.");
            var result = insert(db, contract, integer(contract, "latest_version") + 1, title, content); db.commit(); return result;
        }
    }
    private Map<String, Object> summary(Storage.Tx db, Map<String, Object> contract, Map<String, Object> version) {
        var approval = verified(db, contract, version); var delivery = delivery(db, contract.get("id"), version.get("number"));
        return object("contract_id", contract.get("id"), "author", contract.get("author"), "recipient", contract.get("recipient"),
            "version", version.get("number"), "title", version.get("title"), "created_at", version.get("created_at"),
            "status", approval != null ? "approved" : delivery != null ? "delivered" : "draft", "approval", approval,
            "delivery_id", delivery == null ? null : delivery.get("id"));
    }
    private Object latest(Storage.Tx db, Map<String, Object> contract, String actor) {
        if (actor.equals(contract.get("author"))) return contract.get("latest_version");
        return db.one("SELECT MAX(version) AS number FROM deliveries WHERE contract_id=?", contract.get("id")).get("number");
    }
    List<Map<String, Object>> list(String actor) {
        try (var db = storage.begin(false)) {
            var result = new ArrayList<Map<String, Object>>();
            for (var contract : db.rows("SELECT * FROM contracts WHERE author=? OR recipient=? ORDER BY created_at DESC", actor, actor)) {
                Object number = latest(db, contract, actor);
                if (number != null) result.add(summary(db, contract, version(db, contract, number, actor)));
            }
            return result;
        }
    }
    List<Map<String, Object>> versions(String actor, String id) {
        try (var db = storage.begin(false)) {
            var contract = contract(db, id, actor); var result = new ArrayList<Map<String, Object>>();
            for (var version : db.rows("SELECT * FROM versions WHERE contract_id=? ORDER BY number DESC", id)) {
                if (actor.equals(contract.get("recipient")) && delivery(db, id, version.get("number")) == null) continue;
                result.add(summary(db, contract, version));
            }
            if (result.isEmpty()) throw new ApiException(404, "계약을 찾을 수 없습니다.");
            return result;
        }
    }
    Map<String, Object> read(String actor, String id, Integer number, boolean approvedOnly) {
        try (var db = storage.begin(false)) {
            var contract = contract(db, id, actor);
            var version = version(db, contract, number == null ? latest(db, contract, actor) : number, actor);
            var result = summary(db, contract, version);
            if (approvedOnly && result.get("approval") == null) throw new ApiException(409, "승인되지 않은 문서입니다.");
            result.put("content", content(contract, version, actor));
            if (approvedOnly) {
                var approval = map(result.get("approval"));
                result.put("envelope", parse(str(version, "envelope"))); result.put("signed_payload", message(contract, version, approval));
                result.put("verification_key", protection.publicKey(str(approval, "approver"), "approval", str(approval, "scheme"))); result.put("signature_valid", true);
            }
            return result;
        }
    }
    Map<String, Object> deliver(String actor, String id, int number) {
        try (var db = storage.begin(true)) {
            var contract = contract(db, id, actor);
            if (!actor.equals(contract.get("author"))) throw new ApiException(403, "계약 작성자만 전달할 수 있습니다.");
            var version = version(db, contract, number, actor); verified(db, contract, version); content(contract, version, actor);
            var result = delivery(db, id, number);
            if (result == null) {
                result = object("id", id(), "contract_id", id, "version", number, "created_at", now());
                db.run("INSERT INTO deliveries VALUES (?, ?, ?, ?)", result.get("id"), id, number, result.get("created_at"));
            }
            db.commit(); return result;
        }
    }
    List<Map<String, Object>> inbox(String actor) {
        try (var db = storage.begin(false)) {
            return db.rows("SELECT d.*, v.title, c.author, c.recipient FROM deliveries d JOIN contracts c ON c.id=d.contract_id " +
                "JOIN versions v ON v.contract_id=d.contract_id AND v.number=d.version WHERE c.recipient=? ORDER BY d.created_at DESC", actor);
        }
    }
    Map<String, Object> deliveryDetail(String actor, String id) {
        Map<String, Object> delivery;
        try (var db = storage.begin(false)) { delivery = db.one("SELECT * FROM deliveries WHERE id=?", id); }
        if (delivery == null) throw new ApiException(404, "전달 내역을 찾을 수 없습니다.");
        return object("delivery", delivery, "document", read(actor, str(delivery, "contract_id"), integer(delivery, "version"), false));
    }
    Map<String, Object> comment(String actor, String id, int number, String content) {
        try (var db = storage.begin(true)) {
            var contract = contract(db, id, actor); var version = version(db, contract, number, actor);
            if (delivery(db, id, number) == null) throw new ApiException(409, "전달한 문서에 의견을 등록할 수 있습니다.");
            verified(db, contract, version);
            var result = object("id", id(), "contract_id", id, "version", number, "author", actor, "content", content, "created_at", now());
            try {
                var commentEnvelope = protection.protect(content, bytes(commentContext(result)), str(contract, "author"), str(contract, "recipient"));
                db.run("INSERT INTO comments VALUES (?, ?, ?, ?, ?, ?)", result.get("id"), id, number, actor, text(commentEnvelope), result.get("created_at"));
            } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Comment protection failed", e); }
            db.commit(); return result;
        }
    }
    List<Map<String, Object>> comments(String actor, String id, int number) {
        try (var db = storage.begin(false)) {
            var contract = contract(db, id, actor); version(db, contract, number, actor);
            var result = db.rows("SELECT * FROM comments WHERE contract_id=? AND version=? ORDER BY created_at", id, number);
            for (var comment : result) {
                String stored = str(comment, "content");
                if (stored.startsWith("{")) {
                    try {
                        var envelope = parse(stored);
                        if (DocumentProtection.ENVELOPE_V2.equals(envelope.get("format")))
                            comment.put("content", protection.open(actor, envelope, bytes(commentContext(comment))));
                    } catch (Exception e) { throw new ApiException(409, "의견의 무결성을 확인할 수 없습니다."); }
                }
            }
            return result;
        }
    }
    private Map<String, Object> commentContext(Map<String, Object> comment) {
        return object("domain", "contract-comment-v1", "id", comment.get("id"), "contract_id", comment.get("contract_id"),
            "version", comment.get("version"), "author", comment.get("author"), "created_at", comment.get("created_at"));
    }
    Map<String, Object> approve(String actor, String id, int number) {
        try (var db = storage.begin(true)) {
            var contract = contract(db, id, actor);
            if (!actor.equals(contract.get("recipient")) || !Directory.user(actor).get("role").equals("reviewer"))
                throw new ApiException(403, "지정된 검토자만 승인할 수 있습니다.");
            var version = version(db, contract, number, actor);
            if (number != integer(contract, "latest_version")) throw new ApiException(409, "최신 버전만 새로 승인할 수 있습니다.");
            content(contract, version, actor); var approval = verified(db, contract, version);
            if (approval == null) {
                approval = object("id", id(), "contract_id", id, "version", number, "approver", actor, "created_at", now(), "scheme", protection.signatureScheme());
                try { approval.put("signature", protection.sign(actor, bytes(message(contract, version, approval)))); }
                catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Approval failed", e); }
                db.run("INSERT INTO approvals VALUES (?, ?, ?, ?, ?, ?, ?)", approval.get("id"), id, number, actor, approval.get("created_at"), approval.get("scheme"), approval.get("signature"));
            }
            db.commit(); return approval;
        }
    }
    List<Map<String, Object>> approvals(String actor, String id) {
        try (var db = storage.begin(false)) {
            var contract = contract(db, id, actor); var result = new ArrayList<Map<String, Object>>();
            if (latest(db, contract, actor) == null) throw new ApiException(404, "계약을 찾을 수 없습니다.");
            for (var version : db.rows("SELECT * FROM versions WHERE contract_id=? ORDER BY number DESC", id)) {
                var approval = verified(db, contract, version); if (approval != null) result.add(approval);
            }
            return result;
        }
    }
    Map<String, Object> publicKeys(String user) {
        Directory.user(user);
        return object("user_id", user, "encryption", protection.publicKey(user, "encryption"), "approval", protection.publicKey(user, "approval"));
    }
    void initialize(boolean seed) {
        try (var db = storage.begin(true)) {
            if (db.one("SELECT 1 FROM metadata WHERE key='initialized'") != null) return;
            if (seed) {
                String[][] samples = {
                    {"장비 유지보수 계약서", "제1조 목적\n노스웍스와 파트너랩은 설비 유지보수 업무를 협력하여 수행합니다.\n\n제2조 기간\n계약 기간은 서명일로부터 12개월입니다.\n\n제3조 검토\n업무 범위와 점검 일정을 확인한 뒤 최종본을 승인합니다."},
                    {"기술 자료 제공 협약", "제1조 제공 범위\n협력 업무에 필요한 기술 자료를 지정된 담당자에게 제공합니다.\n\n제2조 자료 관리\n제공받은 자료는 협약 목적에 한해 사용합니다."}};
                for (int i = 0; i < samples.length; i++) {
                    var contract = object("id", id(), "author", "alice", "recipient", "bob", "created_at", now());
                    db.run("INSERT INTO contracts VALUES (?, ?, ?, 1, ?)", contract.get("id"), "alice", "bob", contract.get("created_at"));
                    insert(db, contract, 1, samples[i][0], samples[i][1]);
                    if (i == 0) db.run("INSERT INTO deliveries VALUES (?, ?, 1, ?)", id(), contract.get("id"), now());
                }
            }
            db.run("INSERT INTO metadata VALUES ('initialized', '1')"); db.commit();
        }
    }
}
