package dev.yoonpay.core.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks a state machine against a full from × to matrix written as text, so every pair is
 * stated explicitly. Cells: A = apply, I = ignore, X = illegal.
 */
final class TransitionTable {

    private TransitionTable() {
    }

    static <S extends Enum<S>> void verify(S[] states, String matrix, BiFunction<S, S, Decision> decide) {
        List<String> rows = matrix.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        assertThat(rows).as("one row per state").hasSize(states.length);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            String[] cells = rows.get(i).split("\\s+");
            assertThat(cells[0]).isEqualTo(states[i].name());
            assertThat(cells).as("row " + cells[0]).hasSize(states.length + 1);
            for (int j = 0; j < states.length; j++) {
                S from = states[i];
                S to = states[j];
                String expected = cells[j + 1];
                String actual;
                try {
                    actual = decide.apply(from, to) == Decision.APPLY ? "A" : "I";
                } catch (IllegalTransitionException e) {
                    actual = "X";
                }
                if (!actual.equals(expected)) {
                    failures.add(from + " -> " + to + ": expected " + expected + " got " + actual);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    static <S extends Enum<S>> void assertIllegal(S from, S to, BiFunction<S, S, Decision> decide) {
        assertThatThrownBy(() -> decide.apply(from, to))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining(from + " -> " + to);
    }
}
