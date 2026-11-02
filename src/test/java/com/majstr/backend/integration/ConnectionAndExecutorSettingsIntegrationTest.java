package com.majstr.backend.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AbstractClassTestingTypeFilter;
import org.springframework.core.type.ClassMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Review B-121, B-123: settings that only mean something once a real pool and a real database hold them. */
class ConnectionAndExecutorSettingsIntegrationTest extends IntegrationTestBase {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired ApplicationContext context;
    @Autowired ThreadPoolTaskScheduler scheduler;

    @Test
    void thePoolFailsFastAndNamesWhatHoldsAConnection() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getConnectionTimeout()).isEqualTo(5_000);
        assertThat(hikari.getLeakDetectionThreshold()).isEqualTo(20_000);
    }

    @Test
    void aRequestsStatementCannotWaitForever() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    void aMigrationIsNotBoundByThatTimeout() throws SQLException {
        // A statement past the limit would stop the deploy, so Flyway connects outside the pool.
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW statement_timeout")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("0");
        }
    }

    @Test
    void everyAsyncMethodNamesAnExecutorThatExists() throws ClassNotFoundException {
        // A plain @Async shares Boot's default pool with whatever else lands there: the bug B-123 was.
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AbstractClassTestingTypeFilter() {
            @Override
            protected boolean match(ClassMetadata metadata) {
                return true;
            }
        });
        List<String> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.majstr.backend")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                Async async = method.getAnnotation(Async.class);
                if (async != null) {
                    found.add(method.toString());
                    assertThat(async.value()).as(method.toString()).isNotBlank();
                    assertThat(context.containsBean(async.value())).as(method.toString()).isTrue();
                }
            }
        }
        assertThat(found).as("the scan found the mail, push and broadcast methods").hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void aPlainAsyncKeepsBootsBoundedExecutor_besideTheNamedOnes() {
        Object boots = context.getBean("applicationTaskExecutor");

        assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor()).isSameAs(boots);
        assertThat(((ThreadPoolTaskExecutor) boots).getQueueCapacity()).isEqualTo(500);
    }

    @Test
    void oneStuckJobDoesNotHoldUpTheOthers() {
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
    }
}
