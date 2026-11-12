package it.wallettracker.logo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Il logo di un conto (V36, docs/loghi.md): della banca, o caricato dall'utente per un conto manuale. */
@Entity
@Table(name = "account_logo")
public class AccountLogo {

    /** Da dove viene il logo. */
    public enum Source {
        /** Dalla banca, con l'elenco di Enable Banking. */
        BANK,
        /** Caricato dall'utente. */
        UPLOAD
    }

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;

    @Column(name = "content_type")
    private String contentType;

    /** L'immagine; null se la banca è stata cercata ma il logo non c'era. */
    @Column(name = "data")
    private byte[] data;

    /** Quando è stato scaricato, caricato, o cercato senza trovarlo. */
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected AccountLogo() {
    }

    public AccountLogo(Long accountId, Source source, String contentType, byte[] data, Instant checkedAt) {
        this.accountId = accountId;
        this.source = source;
        this.contentType = contentType;
        this.data = data;
        this.checkedAt = checkedAt;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Source getSource() {
        return source;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getData() {
        return data;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    /** C'è un'immagine da mostrare. */
    public boolean hasImage() {
        return data != null && data.length > 0;
    }
}
