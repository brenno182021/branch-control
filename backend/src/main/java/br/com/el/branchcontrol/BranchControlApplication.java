package br.com.el.branchcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class BranchControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(BranchControlApplication.class, args);
    }

    @Bean GitRunner gitRunner(AppProperties properties,DatabaseInstanceLease lease) {
        var runner=new GitRunner(properties.timeoutSeconds(),lease::verify);
        lease.onLoss(runner::abortActive);
        return runner;
    }
}
