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

    @org.junit.jupiter.api.Test
    void ids_made_in_the_same_millisecond_sort_in_creation_order() {
        java.util.List<String> made = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            made.add(Ids.uuidV7Hex(1_790_000_000_000L));   // all in one millisecond
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(made);
        java.util.Collections.sort(sorted);
        org.assertj.core.api.Assertions.assertThat(sorted).isEqualTo(made);
        org.assertj.core.api.Assertions.assertThat(new java.util.HashSet<>(made)).hasSize(5000);
    }

    @org.junit.jupiter.api.Test
    void a_clock_going_backwards_never_makes_an_older_looking_id() {
        String later = Ids.uuidV7Hex(1_790_000_001_000L);
        String afterClockJump = Ids.uuidV7Hex(1_790_000_000_000L);
        org.assertj.core.api.Assertions.assertThat(afterClockJump).isGreaterThan(later);
    }
}
