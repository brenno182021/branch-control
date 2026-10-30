package br.com.el.branchcontrol;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;

final class AtomicJsonFile {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private AtomicJsonFile() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(path), type);
    }

    static void write(Path path, Object value, boolean backup) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        byte[] content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Path temporary = Files.createTempFile(target.getParent(), ".branch-json-", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            if (backup && Files.exists(target)) {
                Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
