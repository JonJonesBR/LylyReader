import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audiobook_android import _montar_capitulos_textuais, _titulo_capitulo_textual
from narration_normalizer import (
    eh_apenas_numeros,
    normalize_for_narration,
    remover_digitos_isolados,
    sanitize_text_noise,
)
from smart_cleaner import remove_global_occurrences, smart_clean_paragraphs_for_tts
from utils import corrigir_texto_pdf, remover_ruido_pdf_paginas


class NarrationNormalizerTest(unittest.TestCase):
    def test_normalizes_unambiguous_portuguese_patterns(self):
        text = "Dr. Almeida pagou R$ 1.250,00 em 12/05/2026. cap. 3 n\u00ba 45 10 km 20%"
        normalized = normalize_for_narration(text, "pt")
        self.assertEqual(
            "doutor Almeida pagou mil duzentos e cinquenta reais em "
            "doze de maio de dois mil e vinte e seis. cap\u00edtulo tr\u00eas "
            "n\u00famero quarenta e cinco dez quil\u00f4metros vinte por cento",
            normalized,
        )

    def test_removes_decorative_lines_and_broken_hyphenation(self):
        normalized = normalize_for_narration("inter-\nnacional\n***\n_\u00eanfase_", "pt")
        self.assertEqual("internacional\n\n\u00eanfase", normalized)

    def test_expands_roman_chapter_number(self):
        self.assertEqual("CAP\u00cdTULO um", normalize_for_narration("CAP\u00cdTULO I", "pt"))

    def test_keeps_paragraph_positions_when_cleaning(self):
        cleaned, removed = smart_clean_paragraphs_for_tts(
            ["Primeiro par\u00e1grafo.", "12", "Sra. Helena chegou."],
            "pdf",
            "pt",
        )
        self.assertEqual(["Primeiro par\u00e1grafo.", "", "senhora Helena chegou."], cleaned)
        self.assertEqual(1, removed)

    def test_removes_common_pdf_page_markers(self):
        cleaned, removed = smart_clean_paragraphs_for_tts(
            ["P\u00e1gina 12", "12 / 240", "Page 13", "Texto narrativo com o n\u00famero 12 preservado."],
            "pdf",
            "pt",
        )
        self.assertEqual(["", "", "", "Texto narrativo com o n\u00famero 12 preservado."], cleaned)
        self.assertEqual(3, removed)

    def test_removes_user_phrase_from_all_paragraphs(self):
        cleaned, removed = remove_global_occurrences(
            ["Cabe\u00e7alho do livro", "Texto. Cabe\u00e7alho do livro", "Outro texto."],
            "cabe\u00e7alho do livro",
        )
        self.assertEqual(["", "Texto.", "Outro texto."], cleaned)
        self.assertEqual(2, removed)

    def test_detects_common_plain_text_chapter_headings(self):
        for heading in ("Cap\u00edtulo 1", "CAP\u00cdTULO I", "Chapter One", "Parte I", "Livro Primeiro", "1.", "I."):
            self.assertEqual(heading, _titulo_capitulo_textual(heading))

    def test_builds_structured_chapters_from_pdf_blocks(self):
        result = _montar_capitulos_textuais(
            ["Cap\u00edtulo 1", "Primeiro par\u00e1grafo suficientemente longo.", "Cap\u00edtulo 2", "Segundo par\u00e1grafo suficientemente longo."]
        )
        self.assertEqual(["Cap\u00edtulo 1", "Cap\u00edtulo 2"], [chapter["titulo"] for chapter in result["capitulos"]])
        self.assertEqual([0, 0, 1, 1], [paragraph["capitulo_idx"] for paragraph in result["paragrafos"]])

    def test_corrigir_texto_pdf_removes_hyphenations_and_broken_lines(self):
        # Test word dehyphenation preserving pronouns
        pdf_text = "Esta é uma cons-\ntituição do Estado.\nEle deve dar-\nlhe o documento."
        corrected = corrigir_texto_pdf(pdf_text)
        self.assertIn("constituição", corrected)
        self.assertIn("dar-lhe", corrected)

        # Test broken lines joining
        broken_lines = "Ontem eu fui ao\nsupermercado e comprei\num suco de laranja."
        corrected_lines = corrigir_texto_pdf(broken_lines)
        self.assertEqual("Ontem eu fui ao supermercado e comprei um suco de laranja.", corrected_lines)

    def test_eh_apenas_numeros_detecta_pagina_solta_e_separador_decorativo(self):
        for texto in ("42", "696", "6 9 6", "  1  2  3  "):
            self.assertTrue(eh_apenas_numeros(texto), texto)
        for texto in ("Capítulo 12", "12 de maio", "", "12a"):
            self.assertFalse(eh_apenas_numeros(texto), texto)

    def test_sanitize_text_noise_remove_linha_so_numerica(self):
        texto = "Primeiro parágrafo.\n6 9 6\nSegundo parágrafo."
        limpo = sanitize_text_noise(texto)
        self.assertNotIn("6 9 6", limpo)
        self.assertIn("Primeiro parágrafo.", limpo)
        self.assertIn("Segundo parágrafo.", limpo)

    def test_remover_digitos_isolados_grudado_em_prosa_real(self):
        texto = "Ei! — exclamou, 6 9 6 elevando a voz e batendo com o caneco vazio."
        limpo = " ".join(remover_digitos_isolados(texto).split())
        self.assertEqual(
            "Ei! — exclamou, elevando a voz e batendo com o caneco vazio.", limpo
        )

    def test_remover_digitos_isolados_preserva_numeros_reais(self):
        for texto in ("Ele tinha 42 anos.", "Em 1990 tudo mudou.", "Custou 100 200 reais."):
            self.assertEqual(texto, remover_digitos_isolados(texto))

    def test_sanitize_text_noise_remove_digitos_isolados_grudados_em_prosa(self):
        texto = "levava a palavras 6 9 6 ríspidas quando alguém"
        limpo = sanitize_text_noise(texto)
        self.assertNotIn("6 9 6", limpo)
        self.assertIn("levava a palavras", limpo)
        self.assertIn("ríspidas quando alguém", limpo)

    def test_sanitizes_common_extraction_noise(self):
        noisy = "\uf0feTexto\u00a0com\u200b ru\u00eddo. https://example.com contato@example.com \u201cOl\u00e1\u201d\u2014fim"
        self.assertEqual('Texto com ru\u00eddo. "Ol\u00e1" \u2014 fim', sanitize_text_noise(noisy).strip())

    def test_removes_repeated_pdf_headers_page_markers_and_contact_lines(self):
        pages = [
            "Universidade Exemplo\nP\u00e1gina 1\nCEP: 12345-000\nPrimeiro trecho narrativo.",
            "Universidade Exemplo\n2 / 3\nwww.example.com\nSegundo trecho narrativo.",
            "Universidade Exemplo\n- 3 -\nFone: 1234-5678\nTerceiro trecho narrativo.",
        ]
        cleaned = remover_ruido_pdf_paginas(pages)
        self.assertEqual(
            [
                "Primeiro trecho narrativo.",
                "Segundo trecho narrativo.",
                "Terceiro trecho narrativo.",
            ],
            cleaned,
        )

    def test_pdf_cleanup_preserves_blank_line_paragraph_boundaries(self):
        corrected = corrigir_texto_pdf("Primeiro par\u00e1grafo-\n\nSegundo par\u00e1grafo.")
        self.assertEqual("Primeiro par\u00e1grafo-\n\nSegundo par\u00e1grafo.", corrected)

    def test_smart_clean_paragraphs_with_blacklist(self):
        paragraphs = ["Helena foi ao mercado.", "Este texto contém Helena.", "Texto normal."]
        cleaned, removed = smart_clean_paragraphs_for_tts(
            paragraphs, "txt", "pt", blacklist=["Helena"]
        )
        self.assertEqual(["foi ao mercado.", "Este texto contém .", "Texto normal."], cleaned)
        self.assertTrue(removed >= 2)


if __name__ == "__main__":
    unittest.main()
