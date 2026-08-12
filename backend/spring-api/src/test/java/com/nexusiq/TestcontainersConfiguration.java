package com.nexusiq;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres, not H2 (.claude/rules/testing.md) — and the pgvector-enabled
 * image specifically, matching production (ADR-002, docker-compose.yml), since
 * V1__enable_extensions.sql requires `CREATE EXTENSION vector` to succeed.
 *
 * Kafka runs as an ephemeral Testcontainers broker too, deliberately, rather
 * than letting @SpringBootTest classes fall back to application.yml's
 * localhost:29093 default: tests must stay hermetic and pass in CI even when
 * no local `docker compose up` stack is running (.claude/rules/testing.md).
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

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		// Needs spring-boot-starter-kafka on the classpath, not just spring-kafka:
		// its ApacheKafkaContainerConnectionDetailsFactory is what recognises
		// org.testcontainers.kafka.KafkaContainer (confirmed empirically — with
		// only spring-kafka present, context startup fails with
		// ConnectionDetailsNotFoundException because no factory for this
		// container type is registered at all).
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
	}

}
