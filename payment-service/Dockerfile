FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="Self-Healing Cloud Team" \
      description="Payment Business Microservice"

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/payment-service-1.0.0.jar app.jar

EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=5s --start-period=25s --retries=5 \
  CMD curl -f http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
