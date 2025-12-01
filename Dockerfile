FROM eclipse-temurin:17-jdk

RUN apt-get update && apt-get install -y docker.io

RUN mkdir -p /app
RUN mkdir -p /tmp/compiler

WORKDIR /app

COPY build/libs/*.jar app.jar

ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]