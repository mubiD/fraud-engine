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
import static org.mockito.ArgumentMatchers.anyString;
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
        when(query.executeUpdate()).thenReturn(0);
    }

    @Test
    void run_issuesTwoQueries_createAndDrop() {
        job.run();

        verify(em, times(2)).createNativeQuery(anyString());
    }

    @Test
    void createPartition_targetsCorrectDate_twoDaysAhead() {
        job.run();

        LocalDate expectedTarget = LocalDate.now().plusDays(2);
        String expectedName = "transactions_" + expectedTarget.format(FMT);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());

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
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());

        String createSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.startsWith("CREATE"))
                .findFirst().orElseThrow();

        assertThat(createSql).contains(target.toString());
        assertThat(createSql).contains(next.toString());
    }

    @Test
    void dropPartition_targetsCorrectDate_91DaysAgo() {
        job.run();

        LocalDate expectedExpired = LocalDate.now().minusDays(91);
        String expectedName = "transactions_" + expectedExpired.format(FMT);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());

        String dropSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.startsWith("DROP"))
                .findFirst().orElseThrow();

        assertThat(dropSql).contains(expectedName);
        assertThat(dropSql).contains("DROP TABLE IF EXISTS");
    }

    @Test
    void partitionNames_useBasicIsoDateFormat_digitsOnly() {
        job.run();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());

        List<String> sqls = sqlCaptor.getAllValues();
        // Both partition names in the generated SQL should match transactions_YYYYMMDD
        sqls.forEach(sql -> {
            String name = sql.replaceAll(".*?(transactions_\\d{8}).*", "$1");
            assertThat(name).matches("transactions_\\d{8}");
        });
    }

    @Test
    void executeUpdate_calledForBothQueries() {
        job.run();

        verify(query, times(2)).executeUpdate();
    }
}
