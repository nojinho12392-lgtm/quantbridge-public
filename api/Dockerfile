FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY api/requirements_api.txt /tmp/requirements_api.txt
RUN pip install --upgrade pip \
    && pip install -r /tmp/requirements_api.txt

COPY api ./api
COPY data ./data
COPY quantbridge ./quantbridge
COPY sheets_client.py ./sheets_client.py

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "api.server:app", "--host", "0.0.0.0", "--port", "8000"]
