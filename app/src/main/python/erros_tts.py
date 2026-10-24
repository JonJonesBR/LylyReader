"""
erros_tts.py - Erros de síntese que interrompem a conversão na hora.

ErroDeChave: a API de nuvem recusou ou não recebeu a chave (401/403, chave ausente ou
inválida). Repetir o bloco não resolve, então o core_processor_android aborta a conversão
logo no primeiro erro, sem as rodadas de retry. A mensagem vai ao usuário pelo TtsErrorMapper.
"""


class ErroDeChave(Exception):
    """Chave de API ausente, inválida ou recusada pelo motor de nuvem."""
