package it.wallettracker.auth;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AppPinRepository extends JpaRepository<AppPin, Integer> {

    /**
     * La riga del PIN, "bloccata" fino alla fine della transazione (SELECT ... FOR UPDATE): due tentativi arrivati
     * nello stesso istante aspettano il loro turno. Senza, chi prova i PIN potrebbe mandarne cento insieme, tutti
     * letti con "0 errori finora", e scavalcare il blocco.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AppPin p where p.id = " + AppPin.ID)
    Optional<AppPin> findForUpdate();
}
