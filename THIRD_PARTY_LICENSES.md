# Licenças de terceiros

O código do LylyReader é GPL-3.0-or-later (ver `LICENSE`); este arquivo lista os componentes de terceiros do app.

Este arquivo lista assets/bibliotecas de terceiros embutidos no app que exigem
atribuição de licença, além do que já consta em `LICENSE` (licença do próprio projeto).

## Fonte Literata (`app/src/main/res/font/literata_regular.ttf`)

Copyright 2017 The Literata Project Authors (https://github.com/googlefonts/literata)

Licenciada sob a SIL Open Font License, Versão 1.1 (OFL-1.1). Texto completo da
licença: https://scripts.sil.org/OFL

Uso neste app: fonte serifada opcional para a tela de leitura (Configurações de
Aparência → Tipografia), como alternativa à fonte sans-serif padrão do sistema.

## Fonte OpenDyslexic (`app/src/main/res/font/opendyslexic_regular.otf`)

Copyright (c) 2019-07-29, Abbie Gonzalez (https://abbiecod.es), with Reserved Font Name OpenDyslexic. Copyright (c) 12/2012 - 2019

Licenciada sob a SIL Open Font License, Versão 1.1 (OFL-1.1). Texto completo da
licença: https://scripts.sil.org/OFL (cópia em `app/src/main/assets/licencas/OpenDyslexic-OFL.txt`).
Fonte do projeto: https://github.com/antijingoist/opendyslexic

Uso neste app: fonte opcional para leitores com dislexia, na tela de leitura
(Aparência → Tipografia). O arquivo é usado sem alterações, como a OFL exige para o
nome reservado.

## Pocket TTS 3.3 e runtime ONNX

Os pesos públicos do Pocket TTS sem clonagem, os tokenizers específicos por idioma
(também empacotados como ativos pequenos para corrigir pacotes antigos) e os presets
fixos distribuídos com os pacotes de idioma são licenciados sob CC BY 4.0. Os pacotes
do LylyReader contêm modelos ONNX quantizados, tokenizer e estados KV precomputados;
não incluem encoder de voz nem permitem clonagem. Atribuição e fontes: Kyutai Labs, Pocket TTS 3.3,
https://github.com/kyutai-labs/pocket-tts e
https://huggingface.co/kyutai/pocket-tts-without-voice-cloning.

O runtime C++ PocketTTS.cpp (VolgaGerm) é licenciado sob MIT:
https://github.com/VolgaGerm/PocketTTS.cpp. O LylyReader mantém alterações para
carregar estados KV públicos fixos sem encoder/clonagem. SentencePiece 0.2.1 é
licenciado sob Apache-2.0 (https://github.com/google/sentencepiece); dr_libs é
distribuído sob Unlicense (https://github.com/mackron/dr_libs). O processo Pocket
reutiliza a biblioteca ONNX Runtime já incluída para o Supertonic, licenciada sob
MIT (https://github.com/microsoft/onnxruntime).

Os pesos do modelo e os arquivos de voz são artefatos distintos do código do runtime;
cada pacote offline deve manter sua atribuição CC BY 4.0 junto dos metadados.

## Clonagem de voz (codificador Mimi)

A clonagem usa o codificador de voz (Mimi encoder) do Pocket TTS 3.3. O LylyReader **não distribui
os pesos** desse codificador: o app traz só a estrutura do modelo, sem pesos
(`app/src/main/assets/pocket/mimi_encoder_planta.onnx`, exportada do código do Pocket TTS,
https://github.com/kyutai-labs/pocket-tts), e o mapa de onde cada peso fica no arquivo oficial
(`mimi_encoder_mapa.json`). Os pesos vêm do arquivo oficial que a própria pessoa baixa de
https://huggingface.co/kyutai/pocket-tts com a conta dela, depois de aceitar as condições da Kyutai
(CC BY 4.0, com proibição de clonar voz sem consentimento explícito e legal). O app confere o hash do
arquivo, monta o codificador no aparelho e não usa login, senha nem token. Atribuição: Kyutai Labs,
Pocket TTS 3.3, CC BY 4.0.

## Supertonic (Supertone Inc.)

O diretório `rust/` deriva do código de exemplo do projeto Supertonic
(https://github.com/supertone-inc/supertonic), distribuído sob licença MIT. Os **pesos dos modelos**
(baixados em tempo de execução, não versionados aqui) e os **estilos de voz** em
`app/src/main/assets/supertonic/` vêm do repositório do modelo, https://huggingface.co/Supertone/supertonic,
e seguem a licença própria dele (OpenRAIL-M), não a MIT do código.

## Prévias de voz (`app/src/main/assets/previas/`)

Os MP3 de prévia são frases curtas geradas pelas próprias vozes (`scripts/gerar_previas_offline.py`).
Cada prévia segue a licença da voz que a gerou, **não a GPL do app**:

- `kokoro-preview-*`: Kokoro, Apache-2.0. Exceção: `kokoro-preview-pf-dora` e `kokoro-preview-pm-alex` são geradas
  pelo Paradee-8M pt-BR (Apache-2.0, ver seção abaixo), que hoje dá voz à Dora e ao Alex.
- `supertonic-preview-*`: Supertonic, OpenRAIL-M (ver seção acima).
- `piper-preview-piper-ptbr-cadu`, `-faber`, `-jeff`: Piper, dataset CC0.
- `piper-preview-piper-ptbr-edresson`: Piper, corpus CC BY 4.0.
- `piper-preview-piper-ptbr-razo`: Razo (Forjal, ajuste fino da Faber), MIT —
  https://huggingface.co/Lucasllfs/Razo-piper-voice.
- `piper-preview-piper-enus-ljspeech-medium`, `-norman-medium`: Piper, datasets em domínio público.
- `piper-preview-piper-esmx-claude-high`: Piper, dataset Apache-2.0.
- `piper-preview-piper-ptbr-dii`, `-miro`: CC BY-NC-SA 4.0 (**uso não comercial**).
- `mms-preview-main-pt`: MMS-TTS (Meta), CC BY-NC 4.0 (**uso não comercial**).

Quem redistribuir o app ou estes arquivos para fins comerciais deve remover as prévias de uso não
comercial. As fontes e atribuições de cada voz estão em `PiperVitsModelManager.kt` e na seção abaixo.

## sherpa-onnx, ONNX Runtime e bibliotecas nativas

- `app/libs/sherpa-onnx-static-link-onnxruntime-*.aar`: sherpa-onnx (k2-fsa), Apache-2.0 —
  https://github.com/k2-fsa/sherpa-onnx.
- ONNX Runtime (Microsoft), MIT — https://github.com/microsoft/onnxruntime. A biblioteca nativa do
  Supertonic é baixada do Maven Central por `scripts/fetch-onnxruntime.*` e não é versionada.
- Chaquopy (Python no Android), MIT — https://chaquo.com/chaquopy/.

## Paradee e espeak-ng (vozes Dora e Alex)

- **espeak-ng 1.52.0**, GPL-3.0-or-later — https://github.com/espeak-ng/espeak-ng. Converte o texto em fonemas para o
  Paradee. O código-fonte é baixado pelo CMake no build (tag 1.52.0, SHA-256 fixo em `app/src/main/cpp/CMakeLists.txt`)
  e compilado sem alterações, junto com o `ucd-tools` que vem nele (GPL-3.0-or-later). Os dados de português usados
  (`phontab`, `phonindex`, `phondata`, `intonations`, `pt_dict`, `en_dict`, `lang/roa/pt-BR`) estão em
  `app/src/main/assets/espeak-ng-paradee/`, copiados sem alteração da versão 1.52.0.
- Tabela de equivalência de fonemas do **misaki** (hexgrad), Apache-2.0 — https://github.com/hexgrad/misaki, reproduzida em
  `ParadeeTokens.kt`.
- O processo Paradee reutiliza a biblioteca ONNX Runtime já incluída para o Supertonic (MIT).

## Modelos de voz baixados em tempo de execução (não incluídos no repositório)

Cada pacote mantém sua própria licença; confira antes de redistribuir:
- Kokoro — Apache-2.0.
- Paradee-8M pt-BR (Dora e Alex, destilado do Kokoro-82M) — Apache-2.0, de
  https://huggingface.co/eraldohug/Paradee-8M-PtBr; os dois ONNX int8 são espelhados sem modificação em
  https://huggingface.co/JonJonesBR/lylyreader-tts-models (pasta `paradee/ptbr`).
- Piper (vozes de rhasspy/piper e comunidade) — a licença varia **por voz**; veja o cartão de cada voz. A Razo
  (MIT) foi convertida para o formato sherpa-onnx pelo projeto e é baixada de
  https://huggingface.co/JonJonesBR/lylyreader-tts-models.
- MMS-TTS (Meta) — CC BY-NC 4.0 (**uso não comercial**).
- Supertonic e Pocket TTS — ver seções acima.

## Dependências Python (empacotadas via Chaquopy)

- `edge-tts` 7.3.1 — LGPL-3.0
- `aiohttp` — Apache-2.0 AND MIT
- `httpx` 0.28.1 — BSD-3-Clause
- `mutagen` 1.48.1 — GPL-2.0-or-later
- `Pillow` — MIT-CMU
- `pypdf` 6.21.2 — BSD-3-Clause
- `python-docx` 1.2.0 — MIT
- `ebooklib` 0.20 — AGPL-3.0-or-later
- `beautifulsoup4` 4.15.0 — MIT
- `numpy` — BSD-3-Clause (e outras permissivas)
- `olefile` 0.47 — BSD
- `mobi` 0.4.1 — GPL-3.0-only

Por incluir componentes GPL-3.0 (`mobi`), GPL-2.0-or-later (`mutagen`) e AGPL-3.0-or-later (`ebooklib`), combinados conforme a seção 13 da GPL-3.0, o APK é distribuído sob a GPL-3.0 (ver `LICENSE`).

## Serviços online opcionais

Edge TTS usa um endpoint **não oficial** da Microsoft, sem garantia de continuidade e sujeito aos
termos do serviço. Gemini e ElevenLabs exigem chave de API do próprio usuário. O LylyReader não
oferece nem distribui obras protegidas por direitos autorais; o usuário é responsável pelo conteúdo
que importa.
