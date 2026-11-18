FROM python@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df
LABEL org.opencontainers.image.source="https://github.com/mem0ai/mem0" \
      org.opencontainers.image.revision="94c3fe9f238f3dbf29c9ce98643bd71eb13077cd" \
      org.opencontainers.image.version="2.2.1"
WORKDIR /app
COPY requirements.lock /app/requirements.lock
RUN pip install --no-cache-dir --require-hashes -r /app/requirements.lock
COPY server/ /app/
COPY LICENSE.mem0 /app/LICENSE.mem0
ENV PYTHONUNBUFFERED=1 MEM0_TELEMETRY=false HISTORY_DB_PATH=/app/history/history.db
EXPOSE 8000
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn main:app --host 0.0.0.0 --port 8000"]
