"""
metricas_conversao.py - Instrumentação leve de uma conversão de audiolivro.

Guarda em memória o tempo de cada tentativa de bloco e o tempo de cada etapa (síntese, junção,
timbre, ID3, capítulos, ffprobe), para diagnosticar lentidão sem depender de ferramentas externas.
Custo desprezível: só números e tuplas. Não depende de Chaquopy nem de rede.
"""

import math
import time

ETAPAS = ("sintese", "juncao", "timbre", "id3", "capitulos", "ffprobe")


def ms_desde(inicio: float) -> float:
    """Milissegundos decorridos desde `inicio` (valor de time.perf_counter())."""
    return (time.perf_counter() - inicio) * 1000.0


def _percentil(valores_ordenados: list, p: float) -> int:
    """Percentil pelo método do posto mais próximo (valores já ordenados). Lista vazia devolve 0."""
    n = len(valores_ordenados)
    if n == 0:
        return 0
    posicao = max(1, math.ceil(p / 100.0 * n))
    return int(valores_ordenados[min(posicao, n) - 1])


class MetricasConversao:
    def __init__(self):
        self.motor = ""
        self.voz = ""
        self.workers = 0
        self.total_blocos = 0
        self.tamanho_final_bytes = 0
        self._tentativas = []  # tuplas (idx, ms, ok, tentativa)
        self._etapas_ms = {}

    def definir_contexto(self, motor: str, voz: str, total_blocos: int, workers: int) -> None:
        self.motor = str(motor or "")
        self.voz = str(voz or "")
        self.total_blocos = int(total_blocos or 0)
        self.workers = int(workers or 0)

    def registrar_bloco(self, idx: int, ms: float, ok: bool, tentativa: int = 1) -> None:
        """Uma tentativa de síntese de um bloco. `tentativa` começa em 1; acima de 1 é retry."""
        self._tentativas.append((int(idx), float(ms), bool(ok), int(tentativa)))

    def registrar_etapa(self, nome: str, ms: float) -> None:
        """Soma `ms` ao tempo da etapa `nome` (uma das ETAPAS; outros nomes também são aceitos)."""
        self._etapas_ms[nome] = self._etapas_ms.get(nome, 0.0) + float(ms)

    def definir_tamanho(self, tamanho_bytes: int) -> None:
        self.tamanho_final_bytes = int(tamanho_bytes or 0)

    def resumo(self) -> dict:
        """Dict com números e textos simples (serializável). Ver como_texto para a versão legível."""
        ms_ordenados = sorted(t[1] for t in self._tentativas)
        ok = sum(1 for t in self._tentativas if t[2])
        retries = sum(1 for t in self._tentativas if t[3] > 1)
        return {
            "motor": self.motor,
            "voz": self.voz,
            "workers": self.workers,
            "blocos_total": self.total_blocos,
            "tentativas": len(self._tentativas),
            "blocos_ok": ok,
            "blocos_falhos": len(self._tentativas) - ok,
            "retries": retries,
            "p50_ms": _percentil(ms_ordenados, 50),
            "p95_ms": _percentil(ms_ordenados, 95),
            "max_ms": int(ms_ordenados[-1]) if ms_ordenados else 0,
            "etapas_ms": {nome: int(round(v)) for nome, v in self._etapas_ms.items()},
            "tamanho_bytes": self.tamanho_final_bytes,
        }

    def como_texto(self) -> str:
        r = self.resumo()
        etapas = ", ".join(f"{nome}={ms} ms" for nome, ms in r["etapas_ms"].items()) or "nenhuma"
        return (
            f"motor={r['motor']} voz={r['voz']} workers={r['workers']} blocos={r['blocos_total']} | "
            f"tentativas={r['tentativas']} ok={r['blocos_ok']} falhos={r['blocos_falhos']} "
            f"retries={r['retries']} | por bloco: p50={r['p50_ms']} ms p95={r['p95_ms']} ms "
            f"máx={r['max_ms']} ms | etapas: {etapas} | tamanho={r['tamanho_bytes']} bytes"
        )
