package dev.yoonpay.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YoonServerApplicationTest extends PostgresTest {

    @Test
    void boots_and_applies_every_migration() {
        Integer failed = jdbc.sql("select count(*) from flyway_schema_history where not success")
                .query(Integer.class)
                .single();
        Integer applied = jdbc.sql("select count(*) from flyway_schema_history where success")
                .query(Integer.class)
                .single();

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }
}
