FROM python:3.11-slim

LABEL maintainer="Self-Healing Cloud Team" \
      description="Isolation Forest Machine Learning Inference Microservice"

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY ml-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ml-service/ .

EXPOSE 5000

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
  CMD curl -f http://localhost:5000/health || exit 1

CMD ["python", "app.py"]
