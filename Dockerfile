FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle/wrapper/ gradle/wrapper/
COPY src/main/ src/main/
RUN sh gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-noble
ENV SPRING_PROFILES_ACTIVE=prod \
    NEXTKEY_PDF_FONT=/usr/share/fonts/truetype/nanum/NanumGothic.ttf
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-nanum \
    && test -r "$NEXTKEY_PDF_FONT" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 nextkey \
    && useradd --uid 10001 --gid nextkey --create-home --shell /usr/sbin/nologin nextkey
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/nextkey.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=\"${PORT:-8080}\" -jar /app/nextkey.jar"]
