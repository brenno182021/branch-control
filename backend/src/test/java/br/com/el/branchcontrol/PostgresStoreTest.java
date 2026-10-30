package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PostgresStoreTest {
    @Test void persistsHashedUsersAcrossInstancesAndEnforcesConstraints() {
        var db = new TestDatabase();
        var encoder = new BCryptPasswordEncoder(4);
        var users = new UsersStore(db.source, encoder);
        assertTrue(users.needsSetup());
        var admin = users.setupAdmin("Admin", "Administrador", "Senha-teste-123");
        assertFalse(users.needsSetup());
        assertThrows(AppException.class, () -> users.setupAdmin("outro", "Outro", "Senha-teste-123"));
        var stored = new UsersStore(db.source, encoder).findByUsername("ADMIN").orElseThrow();
        assertTrue(encoder.matches("Senha-teste-123", stored.passwordHash()));
        assertEquals(admin.id(), stored.id());
        assertThrows(AppException.class, () -> users.create("ADMIN", "Duplicado", "Senha-teste-123", Groups.USER));
        assertThrows(AppException.class, () -> users.create("teste", "Teste", "curta", Groups.USER));
        assertThrows(AppException.class, () -> users.update(admin.id(), admin.name(), Groups.USER, true, null));
        assertThrows(AppException.class, () -> users.update(admin.id(), admin.name(), Groups.ADM, false, null));
        assertEquals(1, new JdbcTemplate(db.source).queryForObject("SELECT count(*) FROM bc_user", Integer.class));
    }
    @Test void transactionLockProtectsLastAdministratorAcrossStoreInstances() throws Exception {
        var db = new TestDatabase(); var encoder = new BCryptPasswordEncoder(4);
        var one = new UsersStore(db.source, encoder); var two = new UsersStore(db.source, encoder);
        var a = one.setupAdmin("admin1", "Um", "Senha-teste-123");
        var b = one.create("admin2", "Dois", "Senha-teste-123", Groups.ADM);
        var gate = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var f1 = executor.submit(() -> { gate.await(); try { one.update(a.id(), a.name(), Groups.USER, true, null); return true; } catch (AppException ex) { return false; } });
            var f2 = executor.submit(() -> { gate.await(); try { two.update(b.id(), b.name(), Groups.USER, true, null); return true; } catch (AppException ex) { return false; } });
            gate.countDown(); assertNotEquals(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
            assertEquals(1, one.list().stream().filter(u -> u.enabled() && u.group().equals(Groups.ADM)).count());
        } finally { executor.shutdownNow(); }
    }
}
