# L'immagine Docker del backend (Fase 4, docs/44 parte C).
#
# È un Dockerfile "multi-stage": due immagini una dopo l'altra.
#   1. "build": ha Maven e il JDK completo, e compila il jar;
#   2. quella finale: ha solo il JRE (Java per eseguire, non per compilare) e il jar copiato dalla prima.
# Maven, il codice sorgente e le dipendenze scaricate restano nella prima, che si butta: l'immagine che gira è
# più piccola e contiene meno programmi che un attaccante potrebbe usare.
#
# Si costruisce con docker compose (docker compose build backend), non a mano.

# --- 1. Compilazione ---------------------------------------------------------------------------------------
# La stessa versione di Maven del wrapper (.mvn/wrapper/maven-wrapper.properties) e lo stesso Java 21.
FROM maven:3.10-eclipse-temurin-21-alpine AS build
WORKDIR /src

COPY pom.xml .
COPY src ./src

# --mount=type=cache tiene le dipendenze scaricate (~/.m2) tra una build e l'altra, fuori dall'immagine: la
# seconda volta Maven non riscarica niente. I test qui si saltano (-DskipTests): usano Testcontainers, che vuole
# Docker, e dentro una build Docker non c'è. Si eseguono prima, con ./mvnw test, e nella CI di GitHub.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/backend-*.jar /src/app.jar

# --- 2. L'immagine che gira ---------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Un utente senza privilegi: se qualcuno riuscisse a far eseguire codice al backend, non sarebbe root nel
# container. L'id 1000 è quello del primo utente su quasi tutti i Linux: così i file creati nella cartella .data
# (montata dal PC, vedi docker-compose.yml) restano dell'utente del PC.
RUN addgroup -S -g 1000 wallettracker && adduser -S -u 1000 -G wallettracker wallettracker

# Le stesse cartelle del progetto sul PC: /app/backend (dove gira), /app/.data (la chiave di cifratura) e
# /app/secrets. Così i percorsi relativi di application.yml (../.data/encryption.key, ../secrets/...) valgono
# uguali sul PC e nel container.
WORKDIR /app/backend
RUN mkdir -p /app/.data /app/secrets && chown wallettracker:wallettracker /app/.data
COPY --from=build /src/app.jar app.jar

USER wallettracker
EXPOSE 8080

# MaxRAMPercentage: Java usa al massimo il 75% della memoria data al container (di serie ne userebbe un quarto).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
