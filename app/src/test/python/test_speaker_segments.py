import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).parents[2] / "main" / "python" / "speaker_segments.py"
SPEC = importlib.util.spec_from_file_location("speaker_segments", ROOT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SpeakerSegmentPlanTest(unittest.TestCase):
    def setUp(self):
        self.mapping = {
            "speakers": [{"id": "character:marina", "voice_id": "voice-marina"}],
            "paragraphs": [{
                "index": 0,
                "source_text": "“Eu volto”, disse Marina. Ela saiu.",
                "segments": [
                    {"speaker_id": "character:marina", "text": "“Eu volto”"},
                    {"speaker_id": "narrator", "text": ", disse Marina. Ela saiu."},
                ],
            }],
            "chapters": [{"title": "Capítulo 1", "start_paragraph": 0, "end_paragraph": 0}],
        }

    def test_assigns_character_and_narrator_chunks_and_keeps_chapter_range(self):
        plan = MODULE.build_speaker_chunk_plan(
            "“Eu volto”, disse Marina. Ela saiu.", self.mapping, "voice-narrator", 160
        )

        self.assertIsNotNone(plan)
        self.assertEqual(
            [("“Eu volto”", "voice-marina"), ("disse Marina. Ela saiu.", "voice-narrator")],
            list(zip(plan["chunks"], plan["voices"])),
        )
        self.assertEqual(
            [{"titulo": "Capítulo 1", "inicio_chunk": 0, "fim_chunk": 2}],
            plan["chapters"],
        )

    def test_uses_narrator_for_missing_speaker_voice(self):
        self.mapping["speakers"][0]["voice_id"] = None
        plan = MODULE.build_speaker_chunk_plan(
            "“Eu volto”, disse Marina. Ela saiu.", self.mapping, "voice-narrator", 160
        )

        self.assertEqual(["voice-narrator", "voice-narrator"], plan["voices"])

    def test_rejects_attribution_for_different_text(self):
        plan = MODULE.build_speaker_chunk_plan(
            "Livro alterado", self.mapping, "voice-narrator", 160
        )
        self.assertIsNone(plan)

    def test_pausa_curta_so_entre_trechos_do_mesmo_paragrafo(self):
        mapping = {
            "speakers": [{"id": "character:marina", "voice_id": "voice-marina"}],
            "paragraphs": [
                {"index": 0, "source_text": "“Eu volto”, disse Marina.", "segments": [
                    {"speaker_id": "character:marina", "text": "“Eu volto”"},
                    {"speaker_id": "narrator", "text": ", disse Marina."},
                ]},
                {"index": 1, "source_text": "Ela saiu.", "segments": [
                    {"speaker_id": "narrator", "text": "Ela saiu."},
                ]},
            ],
            "chapters": [],
        }
        plan = MODULE.build_speaker_chunk_plan(
            "“Eu volto”, disse Marina.\n\nEla saiu.", mapping, "voice-narrator", 160
        )
        self.assertEqual(["“Eu volto”", "disse Marina.", "Ela saiu."], plan["chunks"])
        # fala → atribuição (mesmo parágrafo): pausa curta; fim do parágrafo e do livro: padrão (None)
        self.assertEqual([MODULE.PAUSA_ENTRE_VOZES_MS, None, None], plan["pauses"])

    def test_pontuacao_inicial_do_trecho_de_narracao_e_removida(self):
        plan = MODULE.build_speaker_chunk_plan(
            "“Eu volto”, disse Marina.", {
                "speakers": [{"id": "character:marina", "voice_id": "voice-marina"}],
                "paragraphs": [{"index": 0, "source_text": "“Eu volto”, disse Marina.", "segments": [
                    {"speaker_id": "character:marina", "text": "“Eu volto”"},
                    {"speaker_id": "narrator", "text": ", disse Marina."},
                ]}],
                "chapters": [],
            }, "voice-narrator", 160
        )
        self.assertFalse(plan["chunks"][1].startswith(","))

    def test_segmento_com_travessao_removido_e_espaco_inserido_ainda_alinha(self):
        # Caso real: a análise tira o travessão e junta trechos do mesmo falante com um espaço, então o texto do
        # segmento não é subcadeia exata do parágrafo. Antes isso derrubava o livro inteiro para o narrador.
        fonte = "– Já atendo o senhor – ela murmurou, abrindo a porta."
        plan = MODULE.build_speaker_chunk_plan(fonte, {
            "speakers": [{"id": "character:ela", "voice_id": "voice-ela"}],
            "paragraphs": [{"index": 0, "source_text": fonte, "segments": [
                {"speaker_id": "character:ela", "text": "Já atendo o senhor"},
                {"speaker_id": "narrator", "text": "ela murmurou, abrindo a porta."},
            ]}],
            "chapters": [],
        }, "voice-narrator", 160)
        self.assertIsNotNone(plan)
        self.assertEqual(["voice-ela", "voice-narrator"], plan["voices"])
        self.assertEqual("Já atendo o senhor", plan["chunks"][0])
        self.assertEqual("ela murmurou, abrindo a porta.", plan["chunks"][1])

    def test_aspas_e_ponto_ficam_com_o_trecho(self):
        fonte = "As palavras: “ESPOSA E MÃE AMADA”. Leio sempre."
        plan = MODULE.build_speaker_chunk_plan(fonte, {
            "speakers": [],
            "paragraphs": [{"index": 0, "source_text": fonte, "segments": [
                {"speaker_id": "narrator", "text": "As palavras: “ESPOSA E MÃE AMADA” . Leio sempre."},
            ]}],
            "chapters": [],
        }, "voice-narrator", 500)
        self.assertEqual([fonte], plan["chunks"])

    def test_paragrafo_que_nao_alinha_fica_com_o_narrador_sem_derrubar_o_livro(self):
        plan = MODULE.build_speaker_chunk_plan("Um.\n\nDois.", {
            "speakers": [{"id": "character:a", "voice_id": "voice-a"}],
            "paragraphs": [
                {"index": 0, "source_text": "Um.", "segments": [{"speaker_id": "character:a", "text": "texto que nao existe"}]},
                {"index": 1, "source_text": "Dois.", "segments": [{"speaker_id": "character:a", "text": "Dois."}]},
            ],
            "chapters": [],
        }, "voice-narrator", 160)
        self.assertEqual(["Um.", "Dois."], plan["chunks"])
        self.assertEqual(["voice-narrator", "voice-a"], plan["voices"])

    def test_um_capitulo_isolado_usa_so_os_paragrafos_dele(self):
        mapping = {
            "speakers": [{"id": "character:a", "voice_id": "voice-a"}],
            "paragraphs": [
                {"index": 0, "source_text": "Um.", "segments": [{"speaker_id": "narrator", "text": "Um."}]},
                {"index": 1, "source_text": "“Dois”, disse.", "segments": [
                    {"speaker_id": "character:a", "text": "“Dois”"}, {"speaker_id": "narrator", "text": ", disse."}]},
                {"index": 2, "source_text": "Três.", "segments": [{"speaker_id": "narrator", "text": "Três."}]},
            ],
            "chapters": [
                {"title": "A", "start_paragraph": 0, "end_paragraph": 0},
                {"title": "B", "start_paragraph": 1, "end_paragraph": 2},
            ],
        }
        plan = MODULE.build_speaker_chunk_plan("“Dois”, disse.\n\nTrês.", mapping, "voice-narrator", 160)
        self.assertIsNotNone(plan)
        self.assertEqual(["“Dois”", "disse.", "Três."], plan["chunks"])
        self.assertEqual(["voice-a", "voice-narrator", "voice-narrator"], plan["voices"])
        self.assertEqual([], plan["chapters"])


if __name__ == "__main__":
    unittest.main()
