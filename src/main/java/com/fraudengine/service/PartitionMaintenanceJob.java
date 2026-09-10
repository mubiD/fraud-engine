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
// on the range-partitioned transactions table and detach (not drop) partitions older
// than 90 days. The H2 in-memory database used in standalone mode does not support table
// partitioning, so this job must not run there.
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
        detachExpiredPartition();
    }

    private void createUpcomingPartition() {
        LocalDate target = LocalDate.now().plusDays(PRE_CREATE_DAYS);
        LocalDate next   = target.plusDays(1);
        String partitionName = "transactions_" + target.format(FMT);

        // Partition names are generated from LocalDate.BASIC_ISO_DATE (digits only), so there's no injection risk.
        em.createNativeQuery(
            "CREATE TABLE IF NOT EXISTS " + partitionName + " PARTITION OF transactions " +
            "FOR VALUES FROM ('" + target + "'::TIMESTAMPTZ) TO ('" + next + "'::TIMESTAMPTZ)"
        ).executeUpdate();

        log.info("Partition ensured: {}", partitionName);
    }

    private void detachExpiredPartition() {
        LocalDate expired = LocalDate.now().minusDays(RETENTION_DAYS + 1);
        String partitionName = "transactions_" + expired.format(FMT);

        Boolean stillAttached = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_inherits i
                    JOIN pg_class child ON i.inhrelid = child.oid
                    JOIN pg_class parent ON i.inhparent = parent.oid
                    WHERE parent.relname = 'transactions' AND child.relname = :partitionName
                )
                """)
                .setParameter("partitionName", partitionName)
                .getSingleResult();

        if (!Boolean.TRUE.equals(stillAttached)) {
            log.debug("No attached partition {} to detach (already detached or never created)", partitionName);
            return;
        }

        // Detached, not dropped: transaction/fraud-decision records are retention-sensitive
        // (AML-adjacent), so this job must not destroy data unilaterally on a schedule.
        // Detaching removes the table from the active partitioned set (bounding partition
        // count / query planning cost, the operational reason this job exists) while the
        // data survives as an ordinary standalone table under the same name, to be
        // archived and dropped later by a separate, deliberate process: a signed-off
        // retention policy and a cold-storage destination are business decisions, not
        // something to invent inside a maintenance job. Plain DETACH (not CONCURRENTLY) so
        // this can run inside this method's existing @Transactional scope; CONCURRENTLY
        // avoids the brief ACCESS EXCLUSIVE lock on the parent but cannot run inside a
        // transaction block, so it's worth revisiting if that lock window becomes an issue.
        em.createNativeQuery("ALTER TABLE transactions DETACH PARTITION " + partitionName).executeUpdate();
        log.info("Expired partition detached (preserved as standalone table, not dropped): {}", partitionName);
    }
}
