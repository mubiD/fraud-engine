package com.fraudengine.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

// PRODUCTION/DEV/INT/QA/LOAD: registers writer + reader DataSources and routes every
// connection checkout between them via ReplicationRoutingDataSource — no repository or
// service code needs to change, since every read-path query service method is already
// @Transactional(readOnly = true) (see TransactionQueryService), and the only place that
// writes outside a service's own transaction (StandaloneTransactionController) doesn't
// apply under this profile anyway.
//
// @Profile("!standalone"): standalone runs H2 in-memory with no replica concept at all —
// letting Spring Boot's normal auto-configured single DataSource apply there unchanged is
// simpler than routing a single in-memory instance to itself. Spring Boot's
// DataSourceAutoConfiguration backs off automatically once any DataSource bean exists in
// the context, so this doesn't need to explicitly exclude anything — it just needs to not
// register beans under a profile where the H2 stub's own spring.datasource.* shape applies.
//
// The reader defaults to the exact same host as the writer (fraud.datasource.reader.host
// falls back to DB_HOST) — every environment without a real replica configured is a safe
// no-op: reads and writes both land on the one Postgres instance, same as before this
// existed. Only an environment that actually sets DB_REPLICA_HOST (see docker-compose.yml's
// postgres-replica service) gets real read/write separation.
@Configuration
@Profile("!standalone")
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource writerDataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource readerDataSource(DataSourceProperties dataSourceProperties,
                                       @Value("${fraud.datasource.reader.host:}") String replicaHost) {
        // Blank replicaHost (the common case — no DB_REPLICA_HOST configured) means reuse the
        // writer's own resolved URL verbatim, whatever it actually is, instead of
        // reconstructing one from DB_HOST/DB_PORT/DB_NAME — see fraud.datasource.reader.host's
        // comment in application.yml for why the two can diverge.
        String writerUrl = dataSourceProperties.getUrl();
        String readerUrl = replicaHost.isBlank()
                ? writerUrl
                : writerUrl.replaceFirst("(?<=://)[^:/]+", replicaHost);
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .url(readerUrl)
                .build();
    }

    @Bean
    @Primary
    public DataSource routingDataSource(DataSource writerDataSource, DataSource readerDataSource) {
        ReplicationRoutingDataSource routingDataSource = new ReplicationRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.WRITER, writerDataSource);
        targetDataSources.put(DataSourceType.READER, readerDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(writerDataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }
}
