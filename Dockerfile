# ---- Stage 1: build (JDK + Gradle, discarded after build) ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy build files first so dependency layer caches across source changes
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

# Source changes here don't invalidate the cached dependencies layer above
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime (JRE only — this is the image that ships) ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# Pull just the fat jar out of the build stage; everything else is dropped
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
