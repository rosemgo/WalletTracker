package it.wallettracker.logo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Che immagine è, dai primi byte (docs/loghi.md, parte C). Test puri. */
class LogoImageTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
    static final byte[] SVG = "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void theTypeComesFromTheFirstBytes() {
        assertThat(LogoImage.typeOf(PNG, false)).hasValue("image/png");
        assertThat(LogoImage.typeOf(JPEG, false)).hasValue("image/jpeg");
        assertThat(LogoImage.typeOf(WEBP, false)).hasValue("image/webp");
        // Un SVG solo dalle banche; caricato dall'utente no.
        assertThat(LogoImage.typeOf(SVG, true)).hasValue("image/svg+xml");
        assertThat(LogoImage.typeOf(SVG, false)).isEmpty();
    }

    @Test
    void notAnImageOrTooBigIsRefused() {
        assertThat(LogoImage.typeOf("<html><script>".getBytes(StandardCharsets.UTF_8), true)).isEmpty();
        assertThat(LogoImage.typeOf(new byte[] {1, 2}, true)).isEmpty();
        assertThat(LogoImage.typeOf(null, true)).isEmpty();
        byte[] big = new byte[LogoImage.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(LogoImage.typeOf(big, false)).isEmpty();
    }
}
