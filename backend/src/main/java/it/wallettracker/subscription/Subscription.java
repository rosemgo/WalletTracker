package it.wallettracker.subscription;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una decisione dell'utente su una serie di pagamenti a un esercente: è un abbonamento (CONFIRMED) oppure no
 * (DISMISSED). Tabelle V11 e V21: la serie è esercente + valuta + importo, perché lo stesso esercente può avere
 * più abbonamenti con importi diversi (Fastweb: SIM e linea di casa).
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    /** La decisione dell'utente. */
    public enum Status {
        CONFIRMED,
        DISMISSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_key", nullable = false)
    private String merchantKey;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency")
    private Frequency frequency;

    /** L'importo della serie: alla conferma, o al rifiuto. Null solo per i rifiuti di prima della V21. */
    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    protected Subscription() {
    }

    /** "Sì, è un abbonamento". */
    public static Subscription confirmed(String merchantKey, String currency, String name, Frequency frequency,
            BigDecimal expectedAmount) {
        Subscription subscription = new Subscription();
        subscription.merchantKey = merchantKey;
        subscription.currency = currency;
        subscription.status = Status.CONFIRMED;
        subscription.name = name;
        subscription.frequency = frequency;
        subscription.expectedAmount = expectedAmount;
        return subscription;
    }

    /** "No, non è un abbonamento: non suggerirlo più", per la serie con questo importo. */
    public static Subscription dismissed(String merchantKey, String currency, BigDecimal amount) {
        Subscription subscription = new Subscription();
        subscription.merchantKey = merchantKey;
        subscription.currency = currency;
        subscription.status = Status.DISMISSED;
        subscription.expectedAmount = amount;
        return subscription;
    }

    /**
     * Questa decisione riguarda la serie di pagamenti a questo esercente, in questa valuta, con questo importo?
     * Sì se l'importo è vicino a quello della decisione (vedi {@link SubscriptionService#SAME_SERIES}); un
     * rifiuto di prima della V21, senza importo, vale per tutti gli importi.
     */
    public boolean covers(String merchantKey, String currency, BigDecimal amount) {
        return this.merchantKey.equals(merchantKey) && this.currency.equals(currency)
                && (expectedAmount == null || SubscriptionService.sameSeries(amount, expectedAmount));
    }

    public Long getId() {
        return id;
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }
}
