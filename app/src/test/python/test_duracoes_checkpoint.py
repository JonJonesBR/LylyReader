import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

import core_processor_android as core  # noqa: E402
from fabricas_audio import gravar, mp3_falso, wav_falso  # noqa: E402

JH = "0123456789abcdef"


class DuracoesNoCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        os.makedirs(os.path.join(self.pasta, f"job_{JH}"))
        patch.object(core, "TEMP_DIR", self.pasta).start()

    def tearDown(self):
        patch.stopall()
        self.tmp.cleanup()

    def _pf(self) -> str:
        return os.path.join(self.pasta, f"job_{JH}", "progress.json")

    def test_duracoes_vao_para_o_checkpoint_e_voltam(self):
        core._salvar_progresso(JH, {0, 1}, {0: 1000, 1: 2000})
        self.assertEqual({0, 1}, core._carregar_progresso(JH))
        self.assertEqual({0: 1000, 1: 2000}, core._carregar_duracoes(JH))

    def test_duracao_de_bloco_fora_dos_concluidos_nao_e_salva(self):
        core._salvar_progresso(JH, {0}, {0: 1000, 7: 9})
        with open(self._pf()) as f:
            self.assertEqual({"0": 1000}, json.load(f)["duracoes_ms"])

    def test_checkpoint_antigo_sem_duracoes_devolve_vazio(self):
        with open(self._pf(), "w") as f:
            json.dump({"completed": [0, 1]}, f)
        self.assertEqual({0, 1}, core._carregar_progresso(JH))
        self.assertEqual({}, core._carregar_duracoes(JH))

    def test_salvar_sem_duracoes_mantem_o_formato_antigo(self):
        core._salvar_progresso(JH, {2})
        with open(self._pf()) as f:
            self.assertEqual({"completed": [2]}, json.load(f))

    def test_duracao_wav_usa_o_byte_rate_do_cabecalho(self):
        caminho = gravar(os.path.join(self.pasta, "bloco.mp3"), wav_falso(2400))
        self.assertEqual(int(os.path.getsize(caminho) * 1000 / 48000), core._duracao_ms_bloco(caminho))

    def test_duracao_wav_nao_conta_o_cabecalho_de_44_bytes(self):
        # 20 bytes de áudio + 44 de cabeçalho: só o áudio conta (0 ms; contando o cabeçalho daria 1 ms)
        caminho = gravar(os.path.join(self.pasta, "curto.mp3"), wav_falso(10))
        self.assertEqual(0, core._duracao_ms_bloco(caminho))

    def test_timeline_de_wav_sem_duracao_salva_usa_so_o_audio(self):
        # 486 bytes de áudio (10,125 ms) + 44 de cabeçalho: o início do 2º bloco é 10 ms, não 11
        blocos = [gravar(os.path.join(self.pasta, f"w{i}.mp3"), wav_falso(243, semente=i)) for i in range(2)]
        inicios = core._inicios_dos_chunks(blocos, blocos, os.path.join(self.pasta, "x.mp3"), None, None)
        self.assertEqual(10, inicios[0][blocos[1]])

    def test_pausa_real_do_mp3_entra_na_linha_do_tempo(self):
        # Edge 24 kHz (quadro de 24 ms): pausa pedida de 300 ms vira 12 quadros = 288 ms gravados
        quadro_edge = bytes([0xFF, 0xF3, 0x64, 0xC4]) + bytes(140)
        blocos = [gravar(os.path.join(self.pasta, f"e{i}.mp3"), quadro_edge * 40) for i in range(2)]
        pausas = core._pausas_reais(blocos, [0, 1], [300, 300])
        self.assertEqual({blocos[0]: 288.0, blocos[1]: 288.0}, pausas)
        inicios = core._inicios_dos_chunks(blocos, blocos, os.path.join(self.pasta, "x.mp3"), {0: 1000, 1: 500}, pausas)
        self.assertEqual(1288.0, inicios[0][blocos[1]])

    def test_duracao_de_arquivo_ilegivel_e_none(self):
        lixo = gravar(os.path.join(self.pasta, "lixo.mp3"), b"xyz" * 10)
        self.assertIsNone(core._duracao_ms_bloco(lixo))
        self.assertIsNone(core._duracao_ms_bloco(os.path.join(self.pasta, "nao_existe.mp3")))


class TimestampsComDuracoesSalvasTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        self.blocos = [gravar(os.path.join(self.pasta, f"part_{i}.mp3"), mp3_falso(900, semente=i))
                       for i in range(4)]
        self.capitulos = [
            {"titulo": "A", "inicio_chunk": 0, "fim_chunk": 2},
            {"titulo": "B", "inicio_chunk": 2, "fim_chunk": 4},
        ]

    def tearDown(self):
        self.tmp.cleanup()

    def test_usa_as_duracoes_salvas_sem_reler_os_blocos(self):
        duracoes = {0: 1000, 1: 2000, 2: 1500, 3: 500}
        caps = core._calcular_timestamps_capitulos(
            self.capitulos, self.blocos, self.blocos, os.path.join(self.pasta, "inexistente.mp3"), duracoes,
        )
        # Sem o arquivo final, o total vira a soma dos blocos (5000 ms); sem pausa, starts: 0, 1000, 3000, 4500.
        self.assertEqual([
            {"titulo": "A", "inicio_ms": 0, "fim_ms": 3000},
            {"titulo": "B", "inicio_ms": 3000, "fim_ms": 5000},
        ], caps)

    def test_sem_duracoes_salvas_cai_na_leitura_do_arquivo(self):
        with patch.object(core, "_MutagenMP3", side_effect=Exception("sem leitura")):
            caps = core._calcular_timestamps_capitulos(
                self.capitulos, self.blocos, self.blocos, os.path.join(self.pasta, "inexistente.mp3"), None,
            )
        # Sem leitura, cada bloco vale 0 ms: os capítulos continuam existindo, com início 0.
        self.assertEqual(2, len(caps))
        self.assertEqual(0, caps[0]["inicio_ms"])


class TrechosParaSincroniaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        self.blocos = [gravar(os.path.join(self.pasta, f"part_{i}.mp3"), mp3_falso(900, semente=i))
                       for i in range(3)]

    def tearDown(self):
        self.tmp.cleanup()

    def test_cada_trecho_ganha_o_inicio_no_mp3_e_o_comeco_do_texto(self):
        duracoes = {0: 1000, 1: 2000, 2: 500}
        pausas = {self.blocos[0]: 300, self.blocos[1]: 300}
        inicios = core._inicios_dos_chunks(
            self.blocos, self.blocos, os.path.join(self.pasta, "inexistente.mp3"), duracoes, pausas,
        )
        chunks = ["Primeiro trecho.", "Segundo " + "x" * 200, "Terceiro."]
        trechos = core._trechos_para_sincronia(chunks, self.blocos, inicios[0])
        self.assertEqual([0, 1300, 3600], [t["inicio_ms"] for t in trechos])
        self.assertEqual("Primeiro trecho.", trechos[0]["texto"])
        self.assertEqual(core._SINCRONIA_TEXTO_MAX, len(trechos[1]["texto"]))

    def test_bloco_que_falhou_fica_fora(self):
        duracoes = {0: 1000, 2: 500}
        validos = [self.blocos[0], self.blocos[2]]
        inicios = core._inicios_dos_chunks(self.blocos, validos, os.path.join(self.pasta, "x.mp3"), duracoes, {})
        trechos = core._trechos_para_sincronia(["a", "b", "c"], self.blocos, inicios[0])
        self.assertEqual(["a", "c"], [t["texto"] for t in trechos])


if __name__ == "__main__":
    unittest.main()
