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

    @Test
    void aFileOverTheLimitIs413WithTheReason() throws Exception {
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];
        Arrays.fill(sixMegabytes, (byte) 'a');

        HttpResponse<String> response = upload(sixMegabytes);

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
