"""
orcamento_retry.py - Orçamento de tempo das rodadas de retry de uma conversão.

As rodadas globais de reprocessamento (depois da rodada inicial) somam um tempo que pode passar de
25 minutos quando um bloco nunca passa (ex.: Edge com rede ruim). O orçamento limita a soma: ao
esgotar, a conversão para de tentar e segue o caminho de falha já existente.

O relógio é injetável (padrão: time.monotonic), para o teste não precisar dormir.
"""

import time


class OrcamentoRetry:
    def __init__(self, total_s: float, relogio=time.monotonic):
        self._total = max(0.0, float(total_s))
        self._relogio = relogio
        self._inicio = None

    def iniciar(self) -> None:
        """Marca o começo das rodadas de retry. Chamadas seguintes não mudam o início."""
        if self._inicio is None:
            self._inicio = self._relogio()

    def gasto(self) -> float:
        """Segundos já consumidos desde iniciar(). Antes de iniciar, zero."""
        if self._inicio is None:
            return 0.0
        return max(0.0, self._relogio() - self._inicio)

    def restante(self) -> float:
        """Segundos que ainda podem ser usados em retries (nunca negativo)."""
        return max(0.0, self._total - self.gasto())

    def esgotado(self) -> bool:
        return self.restante() <= 0.0
