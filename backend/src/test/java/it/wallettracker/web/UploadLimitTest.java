package it.wallettracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Il limite alla dimensione dei file caricati (spring.servlet.multipart.max-file-size, 5 MB).
 *
 * <p>Qui MockMvc non basta: non passa dal server web vero, e il limite lo applica proprio il server mentre
 * legge la richiesta. Con {@code RANDOM_PORT} Spring avvia Tomcat su una porta libera, e gli mandiamo una
 * richiesta HTTP vera con il client di Java.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestConfiguration.class)
class UploadLimitTest {

    @LocalServerPort
    int port;

    /**
     * Un file appena oltre i 5 MB. Non di più, di proposito: se la richiesta intera supera anche
     * max-request-size (6 MB), Tomcat la rifiuta prima di leggerla, e dopo la risposta chiude la connessione
     * senza leggere il resto (ne "inghiotte" al massimo 2 MB, maxSwallowSize). Il client allora a volte
     * riesce a leggere la risposta e a volte no: un test che dipende dalla fortuna. Con 5,1 MB Tomcat legge
     * il file fino al limite, scarta il poco che resta e risponde normalmente.
     */
    @Test
    void aFileOverTheLimitIs413WithTheReason() throws Exception {
        byte[] justOverTheLimit = new byte[5 * 1024 * 1024 + 100 * 1024];
        Arrays.fill(justOverTheLimit, (byte) 'a');

        HttpResponse<String> response = upload(justOverTheLimit);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Il file è troppo grande: al massimo 5 MB.");
    }

    /** Una richiesta multipart scritta a mano: un solo pezzo, "file", separato dal "boundary". */
    private HttpResponse<String> upload(byte[] content) throws Exception {
        String boundary = "confine-del-test";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"grande.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/imports/preview?accountId=1"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
