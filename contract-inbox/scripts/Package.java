import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

class Package {
    private static final Set<String> OMIT = Set.of("data", "target", "dist", ".git", ".idea", ".vscode", ".gradle", "node_modules", ".venv", "__pycache__", ".pytest_cache");
    private static final Set<String> SUFFIXES = Set.of(".zip", ".jar", ".class", ".db", ".sqlite", ".sqlite3", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".pyc");
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = root.resolve("dist/preliminary-submission.zip");
        if (args.length == 2 && args[0].equals("--output")) output = Path.of(args[1]).toAbsolutePath().normalize();
        else if (args.length != 0) throw new IllegalArgumentException("Usage: java scripts/Package.java [--output path.zip]");
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isDirectory(root.resolve("src")))
            throw new IllegalArgumentException("프로젝트 폴더에서 실행해 주세요.");
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = root.relativize(path);
                boolean omit = false;
                for (Path part : relative) if (OMIT.contains(part.toString())) omit = true;
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (omit || name.equals(".DS_Store") || (name.startsWith(".env") && !name.equals(".env.example"))
                        || SUFFIXES.stream().anyMatch(lower::endsWith) || lower.contains(".sqlite3-") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm")) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("심볼릭 링크 대신 실제 파일을 사용해 주세요: " + relative);
                if (Files.isRegularFile(path) && !path.equals(output)) files.add(path);
            }
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".submission-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Path path : files) {
                    ZipEntry entry = new ZipEntry("contract-inbox/" + root.relativize(path).toString().replace('\\', '/'));
                    entry.setTime(0); zip.putNextEntry(entry); Files.copy(path, zip); zip.closeEntry();
                }
                ZipEntry entry = new ZipEntry("contract-inbox/data/.gitkeep"); entry.setTime(0);
                zip.putNextEntry(entry); zip.closeEntry();
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println(output + " (" + (files.size() + 1) + " files)");
    }
}
