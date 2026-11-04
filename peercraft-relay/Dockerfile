# Builds and runs peercraft-relay. The relay keeps its settings (port, secret, limits)
# in /data/relay.properties, so mount a volume there to keep the same relay code.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon jar

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/libs/peercraft-relay-*.jar /app/peercraft-relay.jar
ENV PEERCRAFT_RELAY_CONFIG=/data/relay.properties
WORKDIR /data
VOLUME /data
EXPOSE 52100/udp
ENTRYPOINT ["java", "-jar", "/app/peercraft-relay.jar"]
