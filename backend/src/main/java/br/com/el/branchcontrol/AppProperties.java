package br.com.el.branchcontrol;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("branch-control")
public record AppProperties(String logDirectory, String zoneId, int timeoutSeconds) {}
