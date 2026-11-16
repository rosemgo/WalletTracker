package it.wallettracker.account;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * La fotografia del saldo di un conto in un giorno (docs/saldi.md, parte L): il valore vero del grafico della
 * liquidità per quel conto e quel giorno. Si scrive solo con {@link BalanceSnapshotRepository#upsert}.
 */
@Entity
@Table(name = "balance_snapshot")
public class BalanceSnapshot {

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private BigDecimal amount;

    protected BalanceSnapshot() {
        // per JPA
    }

    public Long getAccountId() {
        return key.accountId;
    }

    public LocalDate getDate() {
        return key.balanceDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** La chiave: un conto e un giorno. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "account_id")
        private Long accountId;

        @Column(name = "balance_date")
        private LocalDate balanceDate;

        protected Key() {
            // per JPA
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key k && Objects.equals(accountId, k.accountId)
                    && Objects.equals(balanceDate, k.balanceDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, balanceDate);
        }
    }
}
