FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
RUN pip install --no-cache-dir redis==5.2.1 'psycopg[binary]==3.2.9'
COPY infra/job_runner.py infra/job_runner.py
COPY docs/ docs/
COPY data/expert/sample.json data/expert/sample.json
COPY data/fr/README.md data/fr/README.md
USER 65532:65532
ENTRYPOINT ["python", "/app/infra/job_runner.py"]
CMD ["worker"]
