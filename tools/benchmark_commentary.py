#!/usr/bin/env python3
"""Évalue la fidélité du commentaire Ollama sur des positions du laboratoire."""

import argparse
import json
from pathlib import Path
import random
import statistics
import time
from urllib import request


POSITIONS = [(100000, 1), (100000, 9), (99999, 13), (99998, 17),
             (99990, 5), (96001, 18), (51342, 7), (22341, 2)]
FIELDS = ("index", "score_joue", "meilleur_score_immediat", "commentaire")
SYSTEM = (
    "Tu analyses une position d’un jeu de lettres. Retourne un objet JSON. "
    "Dans index, indique la variante dont la marge simulée est la plus élevée. "
    "Recopie exactement score_joue et meilleur_score_immediat. Le commentaire, "
    "en français et en deux phrases au maximum, explique l’écart entre le score "
    "immédiat et la marge simulée à partir des seules données fournies. "
    "L’erreur-type indique l’incertitude. N’invente ni chiffres ni mots."
)


def http_json(base, path, payload=None, timeout=45):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = request.Request(base.rstrip("/") + path, data=data, headers={"Content-Type": "application/json"})
    with request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def positions(studio):
    for game, turn in POSITIONS:
        detail = http_json(studio, f"/api/games/{game}")
        turn = min(turn, detail["turns"])
        review = http_json(studio, f"/api/games/{game}/review?turn={turn}")
        alternatives = list(review["alternatives"])
        random.Random(game * 257 + turn).shuffle(alternatives)
        maximum = max(item["evaluation"] for item in alternatives)
        facts = {
            "score_joue": review["played_score"],
            "meilleur_score_immediat": review["best_score"],
            "variantes": [{
                "index": i, "mot": item["word"] or "passe ou échange",
                "score": item["score"], "marge_simulee": round(item["evaluation"], 2),
                "erreur_type": round(item["uncertainty"], 2),
                "reliquat": item["leave"], "joue": item["played"],
            } for i, item in enumerate(alternatives)],
        }
        # A model sees rounded margins, so all ties at the displayed precision are valid.
        best = [i for i, item in enumerate(alternatives) if round(item["evaluation"], 2) == round(maximum, 2)]
        yield game, turn, facts, best


def review_model(ollama, model, facts):
    properties = {key: {"type": "integer"} for key in FIELDS[:-1]}
    properties["index"].update(minimum=0, maximum=len(facts["variantes"]) - 1)
    properties["commentaire"] = {"type": "string"}
    result = http_json(ollama, "/api/chat", {
        "model": model, "stream": False, "think": False, "keep_alive": "90s",
        "format": {"type": "object", "properties": properties, "required": list(FIELDS),
                   "additionalProperties": False},
        "options": {"temperature": 0, "num_predict": 150, "num_ctx": 2048},
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": json.dumps(facts, ensure_ascii=False)}],
    })
    return json.loads(result["message"]["content"])


def run_model(ollama, model, cases):
    # Warm one position outside the timed sample.
    try:
        review_model(ollama, model, cases[0][2])
    except (OSError, ValueError, KeyError) as exc:
        print(f"{model} · préchauffage incomplet · {exc}", flush=True)
    observations = []
    for game, turn, facts, best in cases:
        started = time.monotonic()
        output, error = None, None
        try:
            output = review_model(ollama, model, facts)
        except (OSError, ValueError, KeyError) as exc:
            error = str(exc)
        elapsed = (time.monotonic() - started) * 1000
        valid = (isinstance(output, dict)
                 and all(type(output.get(key)) is int for key in FIELDS[:-1])
                 and 0 <= output["index"] < len(facts["variantes"])
                 and isinstance(output.get("commentaire"), str) and bool(output["commentaire"].strip()))
        grounded = (valid and output["index"] in best
                    and output["score_joue"] == facts["score_joue"]
                    and output["meilleur_score_immediat"] == facts["meilleur_score_immediat"])
        observations.append({"game": game, "turn": turn, "valid": valid, "grounded": grounded,
                             "ms": round(elapsed), "answer": output, "error": error,
                             "expected": {"indices": best, "score_joue": facts["score_joue"],
                                          "meilleur_score_immediat": facts["meilleur_score_immediat"]}})
    times = sorted(item["ms"] for item in observations if item["valid"])
    result = {"model": model, "cases": len(cases),
              "valid": sum(item["valid"] for item in observations),
              "grounded": sum(item["grounded"] for item in observations),
              "p50_ms": round(statistics.median(times)) if times else None,
              "p95_ms": times[(95 * len(times) + 99) // 100 - 1] if times else None,
              "positions": observations}
    print(f"{model} · {result['grounded']}/{len(cases)} exacts · médiane {result['p50_ms']} ms", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="+")
    parser.add_argument("--studio", default="http://127.0.0.1:8787")
    parser.add_argument("--ollama", default="http://127.0.0.1:11434")
    parser.add_argument("--output", type=Path, default=Path("data/models/commentary-benchmarks.json"))
    args = parser.parse_args()
    cases = list(positions(args.studio))
    result = {"schema": "lettrine.commentary-benchmark.v1",
              "method": "Exact = les deux scores et une variante de marge maximale parmi les options affichées. Latence locale préchauffée sur les réponses valides. Le score ne juge pas la qualité générale du commentaire.",
              "items": [run_model(args.ollama, model, cases) for model in args.models]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
