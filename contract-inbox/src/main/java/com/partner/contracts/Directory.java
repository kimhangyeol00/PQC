package com.partner.contracts;

import java.util.List;
import java.util.Map;

final class Directory {
    static final List<Map<String, String>> ORGANIZATIONS = List.of(
        Map.of("id", "north", "name", "노스웍스", "type", "발주사"),
        Map.of("id", "partner", "name", "파트너랩", "type", "협력사"));
    static final List<Map<String, String>> USERS = List.of(
        Map.of("id", "alice", "name", "김지은", "organization_id", "north", "role", "author"),
        Map.of("id", "bob", "name", "이도윤", "organization_id", "partner", "role", "reviewer"),
        Map.of("id", "carol", "name", "박서연", "organization_id", "partner", "role", "reviewer"),
        Map.of("id", "dave", "name", "정민수", "organization_id", "north", "role", "author"));
    static Map<String, String> user(String id) {
        return USERS.stream().filter(u -> u.get("id").equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(404, "사용자를 찾을 수 없습니다."));
    }
    static boolean exists(String id) { return USERS.stream().anyMatch(u -> u.get("id").equals(id)); }
}
