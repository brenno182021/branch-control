package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UsersStoreTest {
    @TempDir Path directory;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String password = "Senha-Local-Teste-123!";

    @Test void persistsBcryptInsteadOfPlaintextAndReloadsUsers() throws Exception {
        var db = new TestDatabase();
        var store = new UsersStore(db.source, encoder);
        assertTrue(store.needsSetup());
        var admin = store.setupAdmin("Admin.Local", "Administrador", password);
        assertEquals("admin.local", admin.username());
        assertEquals("ADM", admin.group());
        var record = store.findByUsername("ADMIN.LOCAL").orElseThrow();
        assertTrue(record.passwordHash().startsWith("$2"));
        assertTrue(encoder.matches(password, record.passwordHash()));
        String json = JsonCodec.write(store.findByUsername("admin.local").orElseThrow());
        assertFalse(json.contains(password));
        assertFalse(json.contains("\"password\""));
        assertTrue(json.contains("passwordHash"));
        assertFalse(new UsersStore(db.source, encoder).needsSetup());
        assertEquals(admin.id(), new UsersStore(db.source, encoder).list().get(0).id());
        assertThrows(AppException.class, () -> store.setupAdmin("segundo", "Outro", password));
    }

    @Test void rejectsDuplicateUsernameUnknownGroupAndInvalidPasswords() {
        var store = new UsersStore(new TestDatabase().source, encoder);
        store.setupAdmin("admin", "Administrador", password);
        store.create("dev", "Desenvolvimento", password, "USER");
        assertThrows(AppException.class, () -> store.create("DEV", "Repetido", password, "USER"));
        assertThrows(AppException.class, () -> store.create("outro", "Outro", password, "OUTRO"));
        assertThrows(AppException.class, () -> store.create("curto", "Curto", "123", "USER"));
        assertThrows(AppException.class, () -> store.create("longo", "Longo", "á".repeat(40), "USER"));
        assertEquals(2, store.list().size());
    }

    @Test void preservesLastEnabledAdminAndChangesAccountRevision() {
        var store = new UsersStore(new TestDatabase().source, encoder);
        var admin = store.setupAdmin("admin", "Administrador", password);
        assertThrows(AppException.class, () -> store.update(admin.id(), "Admin", "USER", true, ""));
        assertThrows(AppException.class, () -> store.update(admin.id(), "Admin", "ADM", false, ""));
        var dev = store.create("dev", "Dev", password, "USER");
        long before = store.findByUsername("dev").orElseThrow().revision();
        store.update(dev.id(), "Teste", "USER", false, "Outra-Senha-123!");
        var updated = store.findByUsername("dev").orElseThrow();
        assertTrue(updated.revision() > before);
        assertFalse(updated.enabled());
        assertEquals("USER", updated.group());
        assertFalse(encoder.matches(password, updated.passwordHash()));
        assertTrue(encoder.matches("Outra-Senha-123!", updated.passwordHash()));
    }

}
