FROM eclipse-temurin:17-jre
ARG JAR
COPY ${JAR} /app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
