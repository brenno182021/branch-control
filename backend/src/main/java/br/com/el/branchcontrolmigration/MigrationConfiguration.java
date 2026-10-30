package br.com.el.branchcontrolmigration;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/** Bootstraps only datasource/Flyway auto-configuration; never scans Git/HTTP services. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class MigrationConfiguration {}
