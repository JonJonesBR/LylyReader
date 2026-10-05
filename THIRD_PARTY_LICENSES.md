# Licenças de terceiros

Este arquivo lista assets/bibliotecas de terceiros embutidos no app que exigem
atribuição de licença, além do que já consta em `LICENSE` (licença do próprio projeto).

## Fonte Literata (`app/src/main/res/font/literata_regular.ttf`)

Copyright 2017 The Literata Project Authors (https://github.com/googlefonts/literata)

Licenciada sob a SIL Open Font License, Versão 1.1 (OFL-1.1). Texto completo da
licença: https://scripts.sil.org/OFL

Uso neste app: fonte serifada opcional para a tela de leitura (Configurações de
Aparência → Tipografia), como alternativa à fonte sans-serif padrão do sistema.

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

## Supertonic (Supertone Inc.)

O diretório `rust/` e os estilos de voz em `app/src/main/assets/supertonic/` derivam do projeto
Supertonic (https://github.com/supertone-inc/supertonic). O código de exemplo é distribuído sob
licença MIT; os **pesos dos modelos** (baixados em tempo de execução, não versionados aqui) seguem a
licença própria do modelo (OpenRAIL-M) publicada em https://huggingface.co/Supertone/supertonic.

## sherpa-onnx, ONNX Runtime e bibliotecas nativas

- `app/libs/sherpa-onnx-static-link-onnxruntime-*.aar`: sherpa-onnx (k2-fsa), Apache-2.0 —
  https://github.com/k2-fsa/sherpa-onnx.
- ONNX Runtime (Microsoft), MIT — https://github.com/microsoft/onnxruntime. A biblioteca nativa do
  Supertonic é baixada do Maven Central por `scripts/fetch-onnxruntime.*` e não é versionada.
- Chaquopy (Python no Android), MIT — https://chaquo.com/chaquopy/.

## Modelos de voz baixados em tempo de execução (não incluídos no repositório)

Cada pacote mantém sua própria licença; confira antes de redistribuir:
- Kokoro — Apache-2.0.
- Piper (vozes de rhasspy/piper e comunidade) — a licença varia **por voz**; veja o cartão de cada voz.
- MMS-TTS (Meta) — CC BY-NC 4.0 (**uso não comercial**).
- Supertonic e Pocket TTS — ver seções acima.

## Dependências Python (empacotadas via Chaquopy)

`edge-tts` (LGPL-3.0), `aiohttp`, `httpx`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`,
`beautifulsoup4`, `numpy`, `olefile`, `mobi` — cada uma sob sua própria licença de código aberto.

## Serviços online opcionais

Edge TTS usa um endpoint **não oficial** da Microsoft, sem garantia de continuidade e sujeito aos
termos do serviço. Gemini e ElevenLabs exigem chave de API do próprio usuário. O LylyReader não
oferece nem distribui obras protegidas por direitos autorais; o usuário é responsável pelo conteúdo
que importa.
