package br.com.el.branchcontrol;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

final class TestDatabase {
    final DriverManagerDataSource source;
    final String schema;
    TestDatabase() {
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://127.0.0.1:55432/postgres");
        String user = System.getenv().getOrDefault("TEST_DB_USER", "branch_control_test");
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Configure TEST_DB_URL, TEST_DB_USER e TEST_DB_PASSWORD para testes PostgreSQL isolados.");
        var admin = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        initialize();
    }
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
    }
}
