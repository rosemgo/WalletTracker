package it.wallettracker.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Il catalogo delle frasi e il codice Java devono dire la stessa cosa: un codice scritto male nel Java darebbe un
 * errore solo quando quel messaggio serve davvero, davanti all'utente.
 */
class MessageCatalogTest {

    /** Un codice del catalogo: parole minuscole (con trattini) unite da punti, come "import.row.missing-date". */
    private static final Pattern CODE_LITERAL = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");

    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    void everyCodeUsedInTheJavaSourcesIsInTheCatalog() throws IOException {
        Set<String> catalog = catalog().stringPropertyNames();

        // I codici "sicuri": quelli passati a chi costruisce un messaggio. Se uno manca, quel messaggio non si scrive.
        Pattern use = Pattern.compile("(?:Message\\.of|new ApiException|new NotFoundException|new MessageException"
                + "|new RowException|messages\\.text|requireLocal|answer|required)\\([^;]*?" + CODE_LITERAL.pattern());
        Set<String> missing = new TreeSet<>();
        for (String source : sources()) {
            Matcher matcher = use.matcher(source);
            while (matcher.find()) {
                if (!catalog.contains(matcher.group(1))) {
                    missing.add(matcher.group(1));
                }
            }
        }

        assertThat(missing).as("codici usati nel Java che mancano in messages.properties").isEmpty();
    }

    @Test
    void everyCodeInTheCatalogIsUsedInTheJavaSources() throws IOException {
        Set<String> used = new TreeSet<>();
        for (String source : sources()) {
            Matcher matcher = CODE_LITERAL.matcher(source);
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }

        Set<String> unused = new TreeSet<>(catalog().stringPropertyNames());
        unused.removeAll(used);

        assertThat(unused).as("frasi di messages.properties che nessuno usa più").isEmpty();
    }

    /** Un apostrofo scritto una volta sola, una graffa non chiusa: MessageFormat li rifiuta, o perde un pezzo. */
    @Test
    void everySentenceIsAValidMessageFormat() throws IOException {
        Properties catalog = catalog();
        for (String code : catalog.stringPropertyNames()) {
            String sentence = catalog.getProperty(code);
            new MessageFormat(sentence, Locale.ITALIAN); // lancia IllegalArgumentException se la frase è scritta male
            assertThat(sentence.replace("''", "")).as("un apostrofo solo in %s: va scritto due volte", code)
                    .doesNotContain("'");
        }
    }

    @Test
    void sentencesAreWrittenWithTheirParameters() {
        assertThat(TestMessages.text(Message.of("category.duplicate", "Viaggi")))
                .isEqualTo("Esiste già una categoria \"Viaggi\".");
        // l'apostrofo, scritto due volte nel catalogo, esce una volta sola; l'identificativo senza punto delle migliaia
        assertThat(TestMessages.text(Message.of("category.has-budget", "Viaggi")))
                .isEqualTo("C'è un budget per \"Viaggi\": eliminalo prima dalla pagina Budget.");
        assertThat(TestMessages.text(Message.of("not-found.account", 12345L))).isEqualTo("Conto 12345 non trovato");
    }

    @Test
    void singularAndPluralAreChosenByTheSentence() {
        assertThat(TestMessages.text(Message.of("auth.wrong-pin", 1)))
                .isEqualTo("PIN sbagliato. Ancora 1 tentativo prima di una pausa.");
        assertThat(TestMessages.text(Message.of("auth.wrong-pin", 4)))
                .isEqualTo("PIN sbagliato. Ancora 4 tentativi prima di una pausa.");
        assertThat(TestMessages.text(Message.of("duration.minutes", 1L))).isEqualTo("1 minuto");
        assertThat(TestMessages.text(Message.of("duration.minutes", 15L))).isEqualTo("15 minuti");
    }

    @Test
    void aMessageInsideAnotherIsWrittenFirst() {
        Message row = Message.of("import.row-error", 3, Message.of("import.row.missing-date"));

        assertThat(TestMessages.text(row)).isEqualTo("Riga 3: manca la data.");
    }

    @Test
    void aVerbatimTextIsLeftAsItIs() {
        // Le graffe e gli apostrofi della risposta di un servizio non sono segnaposto: non passano da MessageFormat.
        assertThat(TestMessages.text(Message.verbatim("can't parse {model}"))).isEqualTo("can't parse {model}");
    }

    private static Properties catalog() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                MessageCatalogTest.class.getResourceAsStream("/messages.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static java.util.List<String> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).map(MessageCatalogTest::read).toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
