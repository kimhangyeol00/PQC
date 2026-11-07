package com.partner.contracts;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.Security;

@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
    @Bean
    ContractService contracts(@Value("${workspace.directory}") String directory, @Value("${workspace.seed}") boolean seed) throws Exception {
        return openService(Path.of(directory), seed);
    }
    static ContractService openService(Path directory, boolean seed) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Path root = directory.toAbsolutePath(); Files.createDirectories(root); CredentialStore.permissions(root, true);
        try (var channel = FileChannel.open(root.resolve(".initialize.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Path database = root.resolve("contracts.sqlite3");
            boolean freshDatabase = !Files.exists(database);
            var storage = new Storage(database);
            // A legacy-only DB may create its first PQC keypairs once. After that, a DB marker
            // and evidence of PQC records prevent missing keys from being silently replaced.
            boolean allowPqcGeneration = freshDatabase ||
                (!storage.pqcKeysInitialized() && !storage.hasPqcArtifacts());
            var credentials = new CredentialStore(root.resolve("keys"), freshDatabase, allowPqcGeneration);
            storage.markPqcKeysInitialized();
            var service = new ContractService(storage, new DocumentProtection(credentials));
            service.initialize(seed); return service;
        }
    }
}
