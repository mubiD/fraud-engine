package com.fraudengine.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceJobTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock private EntityManager em;
    @Mock private Query query;

    private PartitionMaintenanceJob job;

    @BeforeEach
    void setUp() {
        job = new PartitionMaintenanceJob();
        ReflectionTestUtils.setField(job, "em", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        // Default: the expired partition is still attached, so detach proceeds, matching
        // the common case (the exists-check test below overrides this for the skip case).
        when(query.getSingleResult()).thenReturn(Boolean.TRUE);
    }

    @Test
    void run_whenExpiredPartitionAttached_issuesThreeQueries_createExistsCheckAndDetach() {
        job.run();

        verify(em, times(3)).createNativeQuery(anyString());
    }

    @Test
    void run_whenExpiredPartitionAlreadyDetached_skipsDetachQuery() {
        when(query.getSingleResult()).thenReturn(Boolean.FALSE);

        job.run();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues()).noneMatch(sql -> sql.startsWith("ALTER TABLE"));
    }

    @Test
    void createPartition_targetsCorrectDate_twoDaysAhead() {
        job.run();

        LocalDate expectedTarget = LocalDate.now().plusDays(2);
        String expectedName = "transactions_" + expectedTarget.format(FMT);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(3)).createNativeQuery(sqlCaptor.capture());

        String createSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.startsWith("CREATE"))
                .findFirst().orElseThrow();

        assertThat(createSql).contains(expectedName);
        assertThat(createSql).contains("PARTITION OF transactions");
        assertThat(createSql).contains("FOR VALUES FROM");
    }

    @Test
    void createPartition_boundaryDatesAreConsecutiveDays() {
        job.run();

        LocalDate target = LocalDate.now().plusDays(2);
        LocalDate next   = target.plusDays(1);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(3)).createNativeQuery(sqlCaptor.capture());

        String createSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.startsWith("CREATE"))
                .findFirst().orElseThrow();

        assertThat(createSql).contains(target.toString());
        assertThat(createSql).contains(next.toString());
    }

    @Test
    void detachPartition_checksAttachmentForCorrectDate_91DaysAgo() {
        job.run();

        LocalDate expectedExpired = LocalDate.now().minusDays(91);
        String expectedName = "transactions_" + expectedExpired.format(FMT);

        ArgumentCaptor<String> paramCaptor = ArgumentCaptor.forClass(String.class);
        verify(query).setParameter(eq("partitionName"), paramCaptor.capture());

        assertThat(paramCaptor.getValue()).isEqualTo(expectedName);
    }

    @Test
    void detachPartition_issuesAlterTableDetachPartition_notDrop() {
        job.run();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(3)).createNativeQuery(sqlCaptor.capture());

        LocalDate expectedExpired = LocalDate.now().minusDays(91);
        String expectedName = "transactions_" + expectedExpired.format(FMT);

        String detachSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.startsWith("ALTER TABLE"))
                .findFirst().orElseThrow();

        assertThat(detachSql).contains("DETACH PARTITION");
        assertThat(detachSql).contains(expectedName);
        assertThat(sqlCaptor.getAllValues()).noneMatch(s -> s.contains("DROP TABLE"));
    }

    @Test
    void partitionNames_useBasicIsoDateFormat_digitsOnly() {
        job.run();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(3)).createNativeQuery(sqlCaptor.capture());

        // The exists-check query references the partition name only via a bind parameter,
        // not string-concatenated SQL text, so restrict this literal-text check to the two
        // queries that do embed the name directly (CREATE, ALTER).
        List<String> sqlsWithEmbeddedName = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.startsWith("CREATE") || sql.startsWith("ALTER"))
                .toList();
        assertThat(sqlsWithEmbeddedName).hasSize(2);
        sqlsWithEmbeddedName.forEach(sql -> {
            String name = sql.replaceAll(".*?(transactions_\\d{8}).*", "$1");
            assertThat(name).matches("transactions_\\d{8}");
        });
    }

    @Test
    void executeUpdate_calledForCreateAndDetach_notForExistsCheck() {
        job.run();

        // exists-check uses getSingleResult(), not executeUpdate(); only CREATE and ALTER do.
        verify(query, times(2)).executeUpdate();
    }
}
