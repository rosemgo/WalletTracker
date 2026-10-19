package it.wallettracker.csvimport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Da byte a testo. Un file è una sequenza di byte; per trasformarla in lettere bisogna sapere la
 * "codifica" (encoding). Le banche usano quasi sempre UTF-8, ma i file salvati da Excel in italiano sono
 * spesso in Windows-1252, dove "è" e "€" sono scritti con byte diversi.
 *
 * <p>Proviamo UTF-8 in modo <b>severo</b>: se un byte non è UTF-8 valido, invece di metterci un "?" il
 * decodificatore si ferma, e allora riproviamo con Windows-1252.
 */
final class TextDecoder {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextDecoder() {
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, WINDOWS_1252);
        }
    }
}
