package it.wallettracker.logo;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import it.wallettracker.message.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Il logo di un conto (docs/loghi.md). */
@RestController
@RequestMapping("/api/accounts/{id}/logo")
public class LogoController {

    private final LogoService logoService;

    public LogoController(LogoService logoService) {
        this.logoService = logoService;
    }

    /**
     * {@code GET /api/accounts/3/logo}: l'immagine, 404 se non c'è. Il browser la tiene un giorno (l'indirizzo che usa la
     * pagina ha {@code ?v=}, che cambia quando cambia il logo). L'intestazione di sicurezza non lascia fare niente a un
     * SVG aperto da solo: niente script, niente risorse esterne.
     */
    @GetMapping
    public ResponseEntity<byte[]> get(@PathVariable Long id) {
        // messaggio: Questo conto non ha un logo.
        AccountLogo logo = logoService.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "logo.missing"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(logo.getData());
    }

    /** {@code PUT /api/accounts/3/logo} con un file ("file"): il logo di un conto manuale (204). */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > LogoImage.MAX_BYTES) {
            // messaggio: Il logo deve essere un'immagine PNG, JPEG o WebP, al massimo di 256 KB.
            throw new ApiException(HttpStatus.BAD_REQUEST, "logo.invalid-image");
        }
        logoService.upload(id, file.getBytes(), Instant.now());
    }

    /** {@code DELETE /api/accounts/3/logo}: toglie il logo caricato (204). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        logoService.remove(id);
    }
}
