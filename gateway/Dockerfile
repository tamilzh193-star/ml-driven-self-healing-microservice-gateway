FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="Self-Healing Cloud Team" \
      description="API Gateway & Autonomous Operations Center"

# Install curl for healthcheck and Docker socket operations
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the pre-built Fat JAR from target directory
COPY target/gateway-1.0.0.jar app.jar

# Expose default Gateway port
EXPOSE 8080

# Configure healthcheck using Spring Boot Actuator
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
