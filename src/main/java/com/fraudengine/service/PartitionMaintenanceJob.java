package com.fraudengine.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// STANDALONE STUB: Inactive when running with --spring.profiles.active=standalone.
// In production this job runs nightly (02:00) to pre-create the next daily partition
// on the range-partitioned transactions table and drop partitions older than 90 days.
// The H2 in-memory database used in standalone mode does not support table partitioning,
// so this job must not run there.
@Component
@Profile("!standalone")
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int RETENTION_DAYS = 90;
    private static final int PRE_CREATE_DAYS = 2;

    @PersistenceContext
    private EntityManager em;

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void run() {
        createUpcomingPartition();
        dropExpiredPartition();
    }

    private void createUpcomingPartition() {
        LocalDate target = LocalDate.now().plusDays(PRE_CREATE_DAYS);
        LocalDate next   = target.plusDays(1);
        String partitionName = "transactions_" + target.format(FMT);

        // Partition names are generated from LocalDate.BASIC_ISO_DATE (digits only) — no injection risk.
        em.createNativeQuery(
            "CREATE TABLE IF NOT EXISTS " + partitionName + " PARTITION OF transactions " +
            "FOR VALUES FROM ('" + target + "'::TIMESTAMPTZ) TO ('" + next + "'::TIMESTAMPTZ)"
        ).executeUpdate();

        log.info("Partition ensured: {}", partitionName);
    }

    private void dropExpiredPartition() {
        LocalDate expired = LocalDate.now().minusDays(RETENTION_DAYS + 1);
        String partitionName = "transactions_" + expired.format(FMT);

        em.createNativeQuery("DROP TABLE IF EXISTS " + partitionName).executeUpdate();
        log.info("Expired partition dropped if present: {}", partitionName);
    }
}
