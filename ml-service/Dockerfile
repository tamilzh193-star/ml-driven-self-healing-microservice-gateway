FROM python:3.11-slim

LABEL maintainer="Self-Healing Cloud Team" \
      description="Isolation Forest Machine Learning Inference Microservice"

# Install curl for container healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install dependencies first for Docker layer caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy service codebase, trained models, and dataset
COPY . .

EXPOSE 5000

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
  CMD curl -f http://localhost:5000/health || exit 1

CMD ["python", "app.py"]
