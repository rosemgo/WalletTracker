package it.wallettracker.subscription;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code subscription}. */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByStatus(Subscription.Status status);

    List<Subscription> findByMerchantKeyAndCurrency(String merchantKey, String currency);
}
