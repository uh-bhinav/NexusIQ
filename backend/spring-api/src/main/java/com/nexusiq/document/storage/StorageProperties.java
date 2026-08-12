package com.nexusiq.document.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexusiq.storage")
public record StorageProperties(String provider, String localPath, int maxUploadMb) {}
