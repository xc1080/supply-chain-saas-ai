from app.constants import RRF_K

def rrf_merge(keyword_ids: list[str], vector_ids: list[str], limit: int) -> list[str]:

    scores: dict[str, float] = {}

    def accumulate(ranked: list[str]) -> None:

        for i, pid in enumerate(ranked):
            if not pid:
                continue

            scores[pid] = scores.get(pid, 0.0) + 1.0 / (RRF_K + i + 1)

    accumulate(keyword_ids)
    accumulate(vector_ids)

    sorted_ids = sorted(scores.items(), key=lambda x: (-x[1], x[0]))

    return [pid for pid, _ in sorted_ids[: max(limit, 1)]]
