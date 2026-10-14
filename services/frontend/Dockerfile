# ---------- Étape 1 : compilation (JDK, aucune dépendance Maven) ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY src ./src
RUN javac --release 21 -d classes $(find src -name '*.java') \
 && jar --create --file app.jar --main-class lab.frontend.FrontendApp -C classes .
# Agent Java OpenTelemetry, version figée (pas "latest") pour des builds reproductibles.
# Téléchargé dans l'étape build puis copié : compatible BuildKit, builder legacy et Cloud Build.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.32.0/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN chmod 644 /otel/opentelemetry-javaagent.jar

# ---------- Étape 2 : runtime (JRE seul) ----------
FROM eclipse-temurin:21-jre
# L'agent est présent dans l'image mais N'EST PAS activé ici : le manifest K8s (ou docker-compose)
# l'active via JAVA_TOOL_OPTIONS -> on peut comparer avec / sans agent sans rebuild.
COPY --from=build /otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /build/app.jar /app/app.jar
RUN useradd --uid 10001 --no-create-home app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
