"""Redis worker and periodic vector index scheduler for the local Lettrine stack."""

import argparse
from dataclasses import dataclass
import json
import logging
import math
import os
from pathlib import Path
import re
import signal
import socket
from threading import Event, Thread
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

import psycopg
import redis

STREAM = "lettrine:jobs"
GROUP = "lettrine:workers"
RESULT_TTL = 900
HEARTBEAT_TTL = 30
RECLAIM_MS = 180_000
DIMENSIONS = 1024
STOP = Event()
LOGGER = logging.getLogger("lettrine")
ROOT = Path(__file__).resolve().parents[1]
OLLAMA = os.environ.get("LETTRINE_OLLAMA_URL", "http://host.docker.internal:11434").rstrip("/")
EMBED_MODEL = os.environ.get("LETTRINE_EMBED_MODEL", "qwen3-embedding:0.6b")
EXPERT_GAMES = (19, 20, 939, 940, 4979, 4980, 19999, 20000, 49999, 50000, 99999, 100000)

SCHEDULE_INDEX = """
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
    return redis.call('XADD', KEYS[2], '*', 'kind', 'index', 'job_id', ARGV[1], 'payload', '{}')
end
return nil
"""


@dataclass(frozen=True)
class Document:
    id: str
    title: str
    body: str
    source: str


def queue():
    url = os.environ.get("LETTRINE_REDIS_URL", os.environ.get("REDIS_URL", "redis://redis:6379/0"))
    return redis.Redis.from_url(url, decode_responses=True, socket_connect_timeout=5, socket_timeout=8)


def database():
    dsn = os.environ.get("LETTRINE_DATABASE_URL")
    if dsn:
        return psycopg.connect(dsn, connect_timeout=5, application_name="lettrine-worker")
    return psycopg.connect(
        host=os.environ.get("LETTRINE_PG_HOST", "postgres"),
        dbname="lettrine",
        user="lettrine",
        password=os.environ.get("LETTRINE_PG_PASSWORD", "lettrine-local"),
        connect_timeout=5,
        application_name="lettrine-worker",
    )


def ollama(path, payload, timeout=90):
    request = Request(
        OLLAMA + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"Ollama a répondu HTTP {error.code}. Vérifiez le modèle local.") from error
    except (URLError, TimeoutError) as error:
        raise RuntimeError("Ollama ne répond pas sur le Mac.") from error


def challenge(payload):
    model = payload["model"]
    response = ollama("/api/chat", payload)
    commentary = response.get("message", {}).get("content", "").strip()
    if not commentary:
        raise RuntimeError("Ollama n’a renvoyé aucun commentaire.")
    return {"state": "done", "model": model, "commentary": commentary, "usage": {
        "prompt_tokens": int(response.get("prompt_eval_count", 0)),
        "output_tokens": int(response.get("eval_count", 0)),
    }}


def markdown_documents():
    paths = sorted((ROOT / "docs").glob("*.md"))
    paths.append(ROOT / "data/fr/README.md")
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        sections = re.split(r"(?m)(?=^#{1,3} )", path.read_text(encoding="utf-8"))
        for index, section in enumerate(sections):
            title, _, body = section.strip().partition("\n")
            if body.strip():
                body = re.sub(r"\[([^\]]+)\]\(https?://[^)]+\)", r"\1", body).strip()
                yield Document(f"docs:{relative}:{index}", title.lstrip("# "), body[:3200], relative)


def archive_documents():
    archive = json.loads((ROOT / "data/expert/sample.json").read_text(encoding="utf-8"))
    for game in archive["games"]:
        players = " × ".join(game["players"])
        scores = game.get("final_scores") or game["archive_score_totals"]
        body = f"{players}. Dictionnaire {game['dictionary']}. Score final {scores[0]} à {scores[1]}."
        yield Document(f"archive:{game['id']}", game["title"], body, game["source"])
        recorded = [turn for turn in game["turns"] if turn.get("top_score") is not None and turn.get("word")]
        notable = sorted(recorded, key=lambda turn: turn["top_score"] - turn["score"], reverse=True)[:4]
        for turn in notable:
            player = game["players"][turn["player"]]
            title = f"{player} joue {turn['word']} · {game['date']}"
            body = (
                f"{game['title']}. Tour {turn['index']}, {turn['word']} en {turn['coordinate']} "
                f"marque {turn['score']} points. Maximum immédiat publié {turn['top_score']} points. "
                f"Chevalet {turn['rack']}. Dictionnaire {game['dictionary']}."
            )
            yield Document(f"archive:{game['id']}:{turn['index']}", title, body, game["source"])
    for reference in archive["references"]:
        scores = ", ".join(f"{a} à {b}" for a, b in reference["match_scores"])
        body = " × ".join(reference["players"]) + f". Résultats des manches : {scores}."
        yield Document(f"archive:{reference['id']}", reference["title"], body, reference["source"])
        highlight = reference.get("highlight")
        if highlight:
            title = f"{highlight['word']} en {highlight['coordinate']} · {reference['title']}"
            yield Document(
                f"archive:{reference['id']}:highlight", title, highlight["analysis"], highlight["source"]
            )


def expert_documents(connection):
    rows = connection.execute(
        "SELECT id, payload FROM lab_games WHERE id = ANY(%s) AND a = 3 AND b = 3 ORDER BY id",
        (list(EXPERT_GAMES),),
    )
    for game_id, game in rows:
        placements = [move for move in game["moves"] if move["type"] == "play"]
        for move in sorted(placements, key=lambda move: move["score"], reverse=True)[:2]:
            scores = " à ".join(map(str, game["scores"]))
            squares = ", ".join(chr(65 + tile["row"]) + str(tile["col"] + 1) for tile in move["tiles"])
            title = f"{move['word']} · Expert × Expert #{game_id}"
            body = (
                f"Partie simulée Expert contre Expert, score final {scores}. "
                f"Au tour {move['turn']}, le joueur {move['player'] + 1} marque {move['score']} points "
                f"avec {move['word']}. Chevalet {move['rack']}, sac {move['bag_before']} jetons, cases {squares}."
            )
            source = f"/#parties?game={game_id}&turn={move['turn']}"
            yield Document(f"sim:{game_id}:{move['turn']}", title, body, source)


def embeddings(batch):
    inputs = [f"{doc.title}\n{doc.body}" for doc in batch]
    result = ollama("/api/embed", {"model": EMBED_MODEL, "input": inputs, "dimensions": DIMENSIONS})
    vectors = result["embeddings"]
    if len(vectors) != len(inputs):
        raise RuntimeError("Le nombre de vecteurs générés est incorrect.")
    for vector in vectors:
        if len(vector) != DIMENSIONS or not all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector):
            raise RuntimeError(f"Le modèle d’indexation doit renvoyer {DIMENSIONS} valeurs finies.")
    return vectors


def index():
    with database() as connection:
        connection.execute("CREATE EXTENSION IF NOT EXISTS vector")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS lab_vectors (
                id text PRIMARY KEY,
                title text NOT NULL,
                body text NOT NULL,
                source text NOT NULL,
                embedding vector(1024) NOT NULL
            )
            """
        )
        connection.execute("ALTER TABLE lab_vectors ADD COLUMN IF NOT EXISTS embedding_model text NOT NULL DEFAULT ''")
        documents = [*markdown_documents(), *archive_documents(), *expert_documents(connection)]
        existing = {
            record[0]: record[1:]
            for record in connection.execute("SELECT id,title,body,source,embedding_model FROM lab_vectors")
        }
    changes = [
        doc for doc in documents
        if existing.get(doc.id) != (doc.title, doc.body, doc.source, EMBED_MODEL)
    ]
    document_ids = {doc.id for doc in documents}
    stale = [
        doc_id for doc_id in existing
        if doc_id.startswith(("docs:", "archive:", "sim:")) and doc_id not in document_ids
    ]
    if changes or stale:
        records = []
        for start in range(0, len(changes), 12):
            batch = changes[start:start + 12]
            records.extend(
                (doc.id, doc.title, doc.body, doc.source, json.dumps(vector, separators=(",", ":")), EMBED_MODEL)
                for doc, vector in zip(batch, embeddings(batch), strict=True)
            )
        with database() as connection, connection.cursor() as cursor:
            if stale:
                cursor.execute("DELETE FROM lab_vectors WHERE id = ANY(%s)", (stale,))
            cursor.executemany(
                """
                INSERT INTO lab_vectors (id,title,body,source,embedding,embedding_model)
                VALUES (%s,%s,%s,%s,%s::vector,%s)
                ON CONFLICT (id) DO UPDATE SET title=excluded.title, body=excluded.body,
                source=excluded.source, embedding=excluded.embedding, embedding_model=excluded.embedding_model
                """,
                records,
            )
    LOGGER.info("Index prêt · %s entrées, %s mises à jour", len(documents), len(changes))
    return {"state": "done", "indexed": len(documents), "updated": len(changes)}


def heartbeat(role):
    client = queue()
    while not STOP.is_set():
        try:
            client.set(f"lettrine:{role}:heartbeat", socket.gethostname(), ex=HEARTBEAT_TTL)
        except redis.RedisError:
            LOGGER.exception("Redis indisponible")
        STOP.wait(5)


def finish(client, message_id, job_id, result):
    with client.pipeline(transaction=True) as transaction:
        transaction.set(f"lettrine:job:{job_id}", json.dumps(result, ensure_ascii=False), ex=RESULT_TTL)
        if usage := result.get("usage"):
            transaction.hincrby("lettrine:ollama:usage", "requests", 1)
            for key in ("prompt_tokens", "output_tokens"):
                transaction.hincrby("lettrine:ollama:usage", key, max(0, usage.get(key, 0)))
        transaction.xack(STREAM, GROUP, message_id)
        transaction.xdel(STREAM, message_id)
        transaction.execute()


def process(client, message_id, fields):
    job_id = fields.get("job_id", message_id)
    kind = fields.get("kind", "")
    try:
        if kind == "challenge":
            result = challenge(json.loads(fields["payload"]))
        elif kind == "index":
            result = index()
        else:
            raise RuntimeError("Type de tâche inconnu.")
    except RuntimeError as error:
        LOGGER.warning("Tâche %s (%s) · %s", job_id, kind, error)
        result = {"state": "error", "error": str(error)}
    except Exception:
        LOGGER.exception("Échec de la tâche %s (%s)", job_id, kind)
        result = {"state": "error", "error": "Le traitement en arrière-plan a échoué."}
    finish(client, message_id, job_id, result)


def worker():
    client = queue()
    consumer = f"{socket.gethostname()}-{os.getpid()}"
    while not STOP.is_set():
        try:
            try:
                client.xgroup_create(STREAM, GROUP, id="0", mkstream=True)
            except redis.ResponseError as error:
                if "BUSYGROUP" not in str(error):
                    raise
            break
        except redis.RedisError:
            LOGGER.exception("Connexion de la file d’attente impossible")
            STOP.wait(3)
    Thread(target=heartbeat, args=("worker",), daemon=True).start()
    cursor = "0-0"
    while not STOP.is_set():
        try:
            cursor, reclaimed, _ = client.xautoclaim(STREAM, GROUP, consumer, RECLAIM_MS, start_id=cursor, count=1)
            if reclaimed:
                process(client, *reclaimed[0])
                continue
            messages = client.xreadgroup(GROUP, consumer, {STREAM: ">"}, count=1, block=2000)
            for _, entries in messages:
                for message in entries:
                    process(client, *message)
        except redis.RedisError:
            LOGGER.exception("File d’attente inaccessible")
            STOP.wait(3)


def beat():
    interval = max(30, int(os.environ.get("LETTRINE_INDEX_INTERVAL_SECS", "900")))
    client = queue()
    Thread(target=heartbeat, args=("beat",), daemon=True).start()
    while not STOP.is_set():
        try:
            entry = client.eval(
                SCHEDULE_INDEX, 2, "lettrine:beat:index-lock", STREAM, f"index-{uuid4().hex}", interval
            )
            if entry:
                LOGGER.info("Indexation planifiée · %s", entry)
        except redis.RedisError:
            LOGGER.exception("Planification Redis indisponible")
        STOP.wait(min(10, interval))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("worker", "beat", "health"))
    parser.add_argument("role", nargs="?", choices=("worker", "beat"))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    if args.command == "health":
        if not args.role:
            parser.error("health requiert le rôle worker ou beat")
        return 0 if queue().exists(f"lettrine:{args.role}:heartbeat") else 1
    if args.command == "worker":
        worker()
    else:
        beat()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
