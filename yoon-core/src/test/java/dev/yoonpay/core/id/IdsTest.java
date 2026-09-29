package dev.yoonpay.core.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void ids_carry_a_type_prefix_and_a_uuid_v7() {
        String id = Ids.payment();

        assertThat(id).matches("pay_[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15}");
    }

    @Test
    void ids_created_later_sort_after_earlier_ones() {
        String earlier = Ids.uuidV7Hex(1_000_000L);
        String later = Ids.uuidV7Hex(1_000_001L);

        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void ids_are_unique() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.refund());
        }
        assertThat(ids).doesNotHaveDuplicates();
    }
}
