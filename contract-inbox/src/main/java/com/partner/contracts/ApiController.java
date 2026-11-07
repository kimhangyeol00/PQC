package com.partner.contracts;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@SecurityRequirement(name = "identity")
class ApiController {
    private final ContractService service;
    ApiController(ContractService service) { this.service = service; }
    record NewContract(@NotBlank @Size(max=32) String recipient, @NotBlank @Size(max=200) String title, @NotBlank @Size(max=200000) String content) {}
    record NewVersion(@NotBlank @Size(max=200) String title, @NotBlank @Size(max=200000) String content) {}
    record VersionAction(@NotNull @Min(1) Integer version) {}
    record NewComment(@NotNull @Min(1) Integer version, @NotBlank @Size(max=4000) String content) {}

    @GetMapping("/users") Object users() { return Directory.USERS; }
    @GetMapping("/users/{user}") Object user(@PathVariable String user) { return Directory.user(user); }
    @GetMapping("/users/{user}/public-keys") Object keys(@PathVariable String user) { return service.publicKeys(user); }
    @GetMapping("/organizations") Object organizations() { return Directory.ORGANIZATIONS; }
    @GetMapping("/contracts") Object contracts(@RequestHeader("X-User") String actor) { return service.list(actor); }
    @PostMapping("/contracts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@RequestHeader("X-User") String actor, @Valid @RequestBody NewContract body) {
        return service.create(actor, body.recipient(), body.title(), body.content());
    }
    @GetMapping("/contracts/{id}") Object read(@RequestHeader("X-User") String actor, @PathVariable String id) { return service.read(actor, id, null, false); }
    @GetMapping("/contracts/{id}/versions") Object versions(@RequestHeader("X-User") String actor, @PathVariable String id) { return service.versions(actor, id); }
    @PostMapping("/contracts/{id}/versions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object newVersion(@RequestHeader("X-User") String actor, @PathVariable String id, @Valid @RequestBody NewVersion body) {
        return service.createVersion(actor, id, body.title(), body.content());
    }
    @GetMapping("/contracts/{id}/versions/{number}")
    Object version(@RequestHeader("X-User") String actor, @PathVariable String id, @PathVariable @Min(1) int number) { return service.read(actor, id, number, false); }
    @PostMapping("/contracts/{id}/deliveries") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object deliver(@RequestHeader("X-User") String actor, @PathVariable String id, @Valid @RequestBody VersionAction body) { return service.deliver(actor, id, body.version()); }
    @GetMapping("/inbox") Object inbox(@RequestHeader("X-User") String actor) { return service.inbox(actor); }
    @GetMapping("/deliveries/{id}") Object delivery(@RequestHeader("X-User") String actor, @PathVariable String id) { return service.deliveryDetail(actor, id); }
    @PostMapping("/contracts/{id}/comments") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object comment(@RequestHeader("X-User") String actor, @PathVariable String id, @Valid @RequestBody NewComment body) { return service.comment(actor, id, body.version(), body.content()); }
    @GetMapping("/contracts/{id}/comments")
    Object comments(@RequestHeader("X-User") String actor, @PathVariable String id, @RequestParam @Min(1) int version) { return service.comments(actor, id, version); }
    @PostMapping("/contracts/{id}/approvals") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object approve(@RequestHeader("X-User") String actor, @PathVariable String id, @Valid @RequestBody VersionAction body) { return service.approve(actor, id, body.version()); }
    @GetMapping("/contracts/{id}/approvals") Object approvals(@RequestHeader("X-User") String actor, @PathVariable String id) { return service.approvals(actor, id); }
    @GetMapping("/contracts/{id}/approved-copy")
    ResponseEntity<Object> copy(@RequestHeader("X-User") String actor, @PathVariable String id, @RequestParam(required=false) @Min(1) Integer version) {
        var document = service.read(actor, id, version, true);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"contract-" + document.get("contract_id") + "-v" + document.get("version") + ".json\"").body(document);
    }
}
