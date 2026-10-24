"""
velocidade_motores.py - Medição da velocidade de síntese por motor (ms por caractere).

Após cada conversão bem-sucedida, o tempo de síntese dos blocos feitos nesta execução vira uma
amostra de ms_por_char. As amostras entram numa média móvel exponencial e ficam em
`velocidade_motores.json` (em FILES_DIR), lido pela estimativa de tempo do lado Kotlin
(EstimativaDeGeracao.kt). Formato:
    {"<motor>": {"ms_por_char": 12.5, "amostras": 3}}

Tudo aqui é tolerante a falha: nenhuma função levanta exceção para quem chama.
"""

import json
import math
import os

ARQUIVO = "velocidade_motores.json"
ALFA = 0.3                 # peso da amostra nova na média móvel exponencial
MIN_BLOCOS_NOVOS = 20      # execuções menores que isso não medem nada
MAX_FRACAO_FALHOS = 0.10   # acima disso a execução é descartada

MOTORES_MEDIDOS = ("edge", "android", "onnx", "kokoro", "pocket", "piper")


def motor_medido(voz: str, motor_roteado: str):
    """Nome do motor usado nas medições, a partir do motor que converter_tts_auto escolheu
    (ver tts.motor_da_voz). Devolve None para motores que não entram na medição (nuvem).
    Pacote local ("::") que não seja kokoro/mms/byom é Piper."""
    if motor_roteado == "kokoro":
        if "::" in voz:
            minusculo = voz.lower()
            if "mms-" in minusculo or "byom" in minusculo:
                return "kokoro"
            return "piper"
        return "kokoro"
    if motor_roteado in MOTORES_MEDIDOS:
        return motor_roteado
    return None


def ms_por_char(ms_sintese: float, chars: int):
    """Tempo de síntese por caractere. None se não há caracteres ou tempo positivo."""
    try:
        ms = float(ms_sintese)
        n = int(chars)
    except (TypeError, ValueError):
        return None
    if n <= 0 or not math.isfinite(ms) or ms <= 0:
        return None
    return ms / n


def nova_media(anterior, amostra: float) -> float:
    """Média móvel exponencial. Sem média anterior (ou inválida), a primeira amostra substitui."""
    if anterior is None or not isinstance(anterior, (int, float)) or not math.isfinite(anterior):
        return float(amostra)
    return ALFA * float(amostra) + (1.0 - ALFA) * float(anterior)


def execucao_valida(blocos_novos: int, falhos: int) -> bool:
    """Só mede execuções com pelo menos MIN_BLOCOS_NOVOS blocos feitos e até 10% de falhas."""
    try:
        novos = int(blocos_novos)
        ruins = int(falhos)
    except (TypeError, ValueError):
        return False
    if novos < MIN_BLOCOS_NOVOS or ruins < 0:
        return False
    return ruins <= MAX_FRACAO_FALHOS * (novos + ruins)


def aplicar_amostra(dados: dict, motor: str, amostra_ms_por_char: float) -> dict:
    """Devolve um novo dict com a amostra incorporada ao motor. Não altera `dados`."""
    atual = dados.get(motor) if isinstance(dados, dict) else None
    anterior_ms = None
    amostras = 0
    if isinstance(atual, dict):
        valor = atual.get("ms_por_char")
        if isinstance(valor, (int, float)) and math.isfinite(valor):
            anterior_ms = float(valor)
        n = atual.get("amostras", 0)
        if isinstance(n, int) and not isinstance(n, bool) and n > 0:
            amostras = n
    novo = dict(dados) if isinstance(dados, dict) else {}
    novo[motor] = {
        "ms_por_char": round(nova_media(anterior_ms, amostra_ms_por_char), 4),
        "amostras": amostras + 1,
    }
    return novo


def ler_medicoes(caminho: str) -> dict:
    """Lê o JSON de medições. Arquivo ausente, corrompido ou com entradas inválidas devolve {}
    (ou só as entradas válidas)."""
    try:
        with open(caminho, "r", encoding="utf-8") as f:
            bruto = json.load(f)
    except Exception:
        return {}
    if not isinstance(bruto, dict):
        return {}
    limpo = {}
    for motor, entrada in bruto.items():
        if not isinstance(entrada, dict):
            continue
        valor = entrada.get("ms_por_char")
        n = entrada.get("amostras", 0)
        if isinstance(valor, (int, float)) and math.isfinite(valor) and isinstance(n, int) \
                and not isinstance(n, bool) and n >= 0:
            limpo[motor] = {"ms_por_char": float(valor), "amostras": n}
    return limpo


def gravar_medicoes(caminho: str, dados: dict) -> bool:
    """Grava o JSON por escrita atômica (arquivo temporário + os.replace). Devolve False em falha."""
    temporario = caminho + ".tmp"
    try:
        with open(temporario, "w", encoding="utf-8") as f:
            json.dump(dados, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(temporario, caminho)
        return True
    except Exception:
        try:
            if os.path.exists(temporario):
                os.remove(temporario)
        except Exception:
            pass
        return False


def registrar_execucao(pasta: str, motor: str, ms_sintese: float, chars: int,
                       blocos_novos: int, falhos: int) -> bool:
    """Ponto de entrada da conversão: calcula a amostra, aplica e grava. Devolve True se gravou.
    Execução fora dos critérios, motor inválido ou pasta vazia apenas não grava nada."""
    try:
        if not pasta or not motor or not execucao_valida(blocos_novos, falhos):
            return False
        amostra = ms_por_char(ms_sintese, chars)
        if amostra is None:
            return False
        caminho = os.path.join(pasta, ARQUIVO)
        atualizado = aplicar_amostra(ler_medicoes(caminho), motor, amostra)
        return gravar_medicoes(caminho, atualizado)
    except Exception:
        return False
