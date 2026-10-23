import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from utils import remover_secao_notas_final
except ImportError:  # dependências de extração ausentes no ambiente de teste
    remover_secao_notas_final = None


def _corpo(paragrafos: int) -> str:
    return "\n".join(
        f"Parágrafo {i} do livro, com texto suficiente para ocupar espaço no arquivo." for i in range(paragrafos)
    )


def _itens(n: int) -> str:
    return "\n".join(f"{i}. Processo nº {1000 + i}, disponível em: <https://exemplo.gov.br/{i}>" for i in range(1, n + 1))


@unittest.skipIf(remover_secao_notas_final is None, "módulo utils indisponível")
class RemoverSecaoNotasFinalTest(unittest.TestCase):
    def test_corta_notas_no_fim_com_itens_numerados(self):
        texto = _corpo(60) + "\n\nNotas\n" + _itens(12)

        resultado = remover_secao_notas_final(texto)

        self.assertEqual(_corpo(60), resultado)
        self.assertNotIn("Notas", resultado)

    def test_nao_corta_quando_notas_esta_no_meio_do_livro(self):
        texto = _corpo(10) + "\n\nNotas\n" + _itens(12) + "\n" + _corpo(90)

        self.assertEqual(texto, remover_secao_notas_final(texto))

    def test_nao_corta_sem_itens_numerados_suficientes(self):
        texto = _corpo(60) + "\n\nNotas\n" + _itens(9)

        self.assertEqual(texto, remover_secao_notas_final(texto))

    def test_texto_sem_titulo_fica_intacto(self):
        texto = _corpo(60) + "\n" + _itens(15)

        self.assertEqual(texto, remover_secao_notas_final(texto))

    def test_titulo_com_outro_texto_na_linha_nao_conta(self):
        texto = _corpo(60) + "\n\nNotas da edição revisada\n" + _itens(12)

        self.assertEqual(texto, remover_secao_notas_final(texto))

    def test_titulo_e_case_insensitive_e_aceita_referencias_sem_acento(self):
        texto = _corpo(60) + "\n\nREFERENCIAS\n" + _itens(10)

        self.assertEqual(_corpo(60), remover_secao_notas_final(texto))

    def test_titulo_em_ingles_endnotes(self):
        texto = _corpo(60) + "\n\nEndnotes\n" + _itens(10)

        self.assertEqual(_corpo(60), remover_secao_notas_final(texto))

    def test_usa_o_ultimo_titulo_de_notas(self):
        # "Notas" aparece no meio e no fim; só o último, nos 30% finais, conta.
        texto = _corpo(20) + "\n\nNotas\n" + _itens(12) + "\n" + _corpo(80) + "\n\nNotas\n" + _itens(12)

        resultado = remover_secao_notas_final(texto)

        self.assertTrue(resultado.endswith(_corpo(80)))
        self.assertIn("Notas\n" + _itens(12), resultado)

    def test_texto_vazio_devolve_vazio(self):
        self.assertEqual("", remover_secao_notas_final(""))


if __name__ == "__main__":
    unittest.main()
