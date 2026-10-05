import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from tts import _KOKORO_VOZES_PT, _qualquer_voz_pt


class VozesKokoroPtTest(unittest.TestCase):
    """RN-1 do PLANO_MELHORIAS_V6 no lado Python: as vozes pt do Kokoro precisam ser
    reconhecidas como pt-BR pelo dispatcher — se não, a normalização do texto (números,
    datas, moedas) roda em inglês antes da síntese."""

    def test_tres_vozes_pt_kokoro_sao_detectadas_como_pt(self):
        for voz in ("kokoro-pf-dora", "kokoro-pm-alex", "kokoro-pm-santa"):
            self.assertTrue(_qualquer_voz_pt(voz), f"{voz} deveria ser detectada como pt")

    def test_lista_explicita_tem_exatamente_as_tres_vozes_pt(self):
        # Se uma voz pt nova for adicionada no VoiceCatalog (Kotlin), este teste quebra até
        # a lista Python ser atualizada junto — proteção contra dessincronização.
        self.assertEqual(
            {"kokoro-pf-dora", "kokoro-pm-alex", "kokoro-pm-santa"},
            _KOKORO_VOZES_PT,
        )

    def test_voz_nao_pt_continua_sem_deteccao_pt(self):
        self.assertFalse(_qualquer_voz_pt("kokoro-af-alloy"))
        self.assertFalse(_qualquer_voz_pt("kokoro-inexistente"))

    def test_voz_byom_com_sufixo_pt_e_detectada(self):
        # BYOMManager (Kotlin) sufixa o id interno com "-pt" quando o manifesto declara
        # idioma pt-* — não precisa de lista nova aqui, só reaproveita a heurística de sufixo.
        self.assertTrue(_qualquer_voz_pt("byom-abc123::voz1-pt"))

    def test_voz_byom_sem_sufixo_pt_nao_e_detectada(self):
        self.assertFalse(_qualquer_voz_pt("byom-abc123::voz1"))

    def test_voz_mms_por_e_detectada_como_pt(self):
        # Parte A do V6 (MMS-TTS) reaproveita o MESMO formato "<packId>::<vozId>" do BYOM,
        # mas o packId ("mms-por") não começa com "byom-" — a heurística de "::" precisa ser
        # genérica (qualquer pacote local), não específica do prefixo "byom-".
        self.assertTrue(_qualquer_voz_pt("mms-por::main-pt"))


if __name__ == "__main__":
    unittest.main()
