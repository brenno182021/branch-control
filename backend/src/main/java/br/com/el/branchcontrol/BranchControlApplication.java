package br.com.el.branchcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import br.com.el.branchcontrolmigration.MigrationConfiguration;
import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class BranchControlApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate-only")) {
            String[] options = Arrays.stream(args).filter(arg -> !arg.equals("--migrate-only")).toArray(String[]::new);
            try (var context = new SpringApplicationBuilder(MigrationConfiguration.class).web(WebApplicationType.NONE).run(options)) {
                context.getBean(org.flywaydb.core.Flyway.class);
            }
        } else SpringApplication.run(BranchControlApplication.class, args);
    }

    @Bean GitRunner gitRunner(AppProperties properties,DatabaseInstanceLease lease) {
        var runner=new GitRunner(properties.timeoutSeconds(),lease::verify);
        lease.onLoss(runner::abortActive);
        return runner;
    }
}
