package com.nexusiq;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres, not H2 (.claude/rules/testing.md) — and the pgvector-enabled
 * image specifically, matching production (ADR-002, docker-compose.yml), since
 * V1__enable_extensions.sql requires `CREATE EXTENSION vector` to succeed.
 */
// public: shared by @SpringBootTest classes in every package, not just com.nexusiq.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
	}

}
