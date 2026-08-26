package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 30A：固定输入区与历史预算的可解释账本。 */
class ContextBudgetLedgerTest {

    @Test
    void separatesFixedInputFromHistoryAndOutputReserve() {
        ContextBudgetLedger ledger = new ContextBudgetLedger(ContextBudget.CallSite.CHAT,
                128_000, 16_000, 2_048, 512, 1_200, 3_400, 600, 16_000);

        assertThat(ledger.fixedInputTokens()).isEqualTo(5_200);
        assertThat(ledger.reservedTokens()).isEqualTo(2_560);
        assertThat(ledger.estimatedInputTokens(16_000)).isEqualTo(21_200);
        assertThat(ledger.constrained()).isFalse();
        assertThat(ledger.fixedPartExceedsWindow()).isFalse();
    }

    @Test
    void marksHistoryAsConstrainedWhenWindowLeavesLessThanConfiguredBudget() {
        ContextBudgetLedger ledger = new ContextBudgetLedger(ContextBudget.CallSite.EXECUTOR,
                8_000, 12_000, 1_000, 500, 2_000, 1_500, 2_500, 2_000);

        assertThat(ledger.constrained()).isTrue();
        assertThat(ledger.estimatedInputTokens(ledger.historyBudgetTokens()))
                .isEqualTo(8_000);
        assertThat(ledger.fixedPartExceedsWindow()).isFalse();

        ContextBudgetLedger overflow = new ContextBudgetLedger(ContextBudget.CallSite.EXECUTOR,
                8_000, 12_000, 1_000, 500, 4_000, 2_500, 2_500, 0);
        assertThat(overflow.fixedPartExceedsWindow()).isTrue();
    }
}
