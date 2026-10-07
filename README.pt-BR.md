# LylyReader Android

Aplicativo Android que converte livros digitais e documentos em arquivos de audiobook diretamente no dispositivo. O projeto combina uma interface nativa em Kotlin com um motor de processamento em Python embarcado para leitura de documentos, limpeza de texto, orquestracao de text-to-speech, cache de audio e metadados de MP3.

Leia este README em [ingles](README.md) ou [espanhol](README.es.md).

<div align="center">

[![Download APK](https://img.shields.io/badge/Download-APK%20v1.8.0-4F46E5?style=for-the-badge)](https://github.com/JonJonesBR/LylyReader-Android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader-Android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![Licenca](https://img.shields.io/badge/Licenca-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Por Que Este Projeto Importa

O LylyReader foi criado como uma ferramenta Android pratica para pessoas que querem ouvir textos longos sem depender de um fluxo de trabalho no desktop. Ele aceita formatos comuns de livros e documentos, extrai texto legivel, permite escolher um motor de voz e exporta um arquivo de audiobook com suporte a reproducao e biblioteca local.

A implementacao e intencionalmente hibrida:

- Kotlin cuida de UI Android, ciclo de vida, intents de arquivo, reproducao, notificacoes, estado de fila e configuracoes do app.
- Python roda dentro do APK por meio do Chaquopy e cuida de parsing, normalizacao de texto, coordenacao do pipeline TTS, cache de audio e metadados.
- O app oferece suporte a varios motores de TTS: Microsoft Edge TTS, Google Gemini TTS, ElevenLabs e OpenRouter TTS online, Supertonic (vozes neurais offline) e o TTS nativo do Android (offline) — as chaves de API sao armazenadas localmente pelo usuario.

## Funcionalidades

- Importacao de arquivos EPUB, PDF, TXT, DOC, DOCX, MOBI e Markdown pelo compartilhamento do Android ou seletor de arquivos.
- Conversao de texto em audiobooks usando motores online (Microsoft Edge TTS, Google Gemini TTS, ElevenLabs, OpenRouter) ou vozes totalmente offline (Supertonic neural, ou o TTS nativo do Android).
- Motor de voz ElevenLabs via API oficial com chave propria (armazenada com seguranca no dispositivo).
- Vozes offline no dispositivo via ONNX Runtime: **Supertonic** (multilingue).
- Modo de leitura guiada: o texto rola suavemente acompanhando a narracao e voce pode mudar a velocidade da voz ao vivo.
- Leitura guiada em segundo plano: a narracao continua ao sair do leitor, com indicador dentro do app e uma notificacao que leva de volta ao paragrafo exato.
- Buffer inteligente de TTS: os proximos paragrafos sao sintetizados antes da reproducao (janela mais profunda para motores online) e um cache de sintese por conteudo e compartilhado entre a leitura guiada e a conversao final, reaproveitando o audio em vez de gerar de novo.
- Atalhos "Continuar lendo" na tela inicial e na biblioteca retomam uma leitura guiada na hora, do ponto onde voce parou.
- Normalizacao inteligente de texto na leitura guiada: expande abreviacoes, simbolos (&,@,+,=,#), datas, porcentagens, moeda, unidades e siglas para pronunciacao natural em PT-BR.
- Compartilhe um trecho do texto do leitor pelo menu de compartilhar do Android.
- Temas de leitura Sepia e Preto OLED, alem de Claro/Escuro, com fonte serifada opcional e controle de margem horizontal do texto.
- Progresso rico no leitor: percentual do livro e tempo estimado restante no capitulo atual.
- Sumario navegavel de capitulos (indice), acessivel a qualquer momento durante a leitura.
- Marque qualquer paragrafo durante a leitura com um toque; uma lista dedicada permite voltar a qualquer ponto salvo.
- Destaques de texto com nota opcional, salvos por livro.
- Definicao de palavra pela selecao de texto, abrindo um dicionario instalado no aparelho ou busca na web.
- Auto-rewind ao retomar um audiobook pausado, com opcoes de recuo curto ou longo configuraveis.
- Fade-out suave de volume nos ultimos segundos do sleep timer, em vez de corte seco.
- Voz e velocidade da leitura guiada salvas por livro, retomadas automaticamente ao reabrir cada titulo.
- Tela de estatisticas de leitura e escuta (minutos ouvidos/lidos, livros concluidos, sequencia de dias), 100% local e privada.
- Tela de estatisticas da biblioteca exibindo total de livros, duracao total e progresso agregado de leitura.
- Ordenacao da biblioteca por progresso (nao concluidos primeiro).
- Backup e restauracao completos (marcadores, destaques, progresso de leitura/escuta, estatisticas e configuracoes) como um unico arquivo ZIP, com mesclagem conservadora na importacao que nunca perde dados locais; uma exportacao/importacao mais leve, so de configuracoes, em JSON, tambem esta disponivel.
- Visualizador de logs interno para troubleshooting, acessivel pelas configuracoes.
- Exclusao de vozes offline instaladas diretamente pela tela de gerenciamento de vozes.
- Sliders com entrada numerica manual; controle rapido de brilho na folha de aparencia do leitor; tooltips e acesso ao tutorial.
- 10 vozes Supertonic PT-BR (F1–F5 / M1–M5) embutidas no APK, com sintese mais rapida em aparelhos de entrada.
- Preview de vozes antes de iniciar conversoes longas.
- Aviso e atalho quando a voz Android selecionada nao tem dados baixados, com link direto para instala-la.
- Configuracao de velocidade da narracao, pausas entre paragrafos, bitrate, qualidade do Supertonic (passos) e pasta de saida.
- Seletor de tema Claro / Escuro / Sistema.
- Timer de soneca com o tempo restante exibido na tela e na notificacao de midia.
- Retomada de conversoes interrompidas com cache de chunks de audio.
- Salvamento de audiobooks gerados com capa e metadados de capitulos quando disponiveis.
- Exportacao dos capitulos do livro como arquivos .txt separados, ou do audiobook como video (MP4).
- Reproducao de audio no app: retoma de onde parou, com indicador de "% ouvido" na biblioteca, alem de segundo plano e controles por notificacao.
- Pule para o capitulo anterior ou proximo direto pela notificacao de midia, controles Bluetooth ou um wearable, quando o audiobook tem marcas de capitulo.
- Navegue e toque sua biblioteca de audiobooks convertidos pelo Android Auto.
- Alterne entre ler e ouvir o mesmo livro: abra o audiobook correspondente a partir do leitor depois de convertido, ou volte ao texto de origem a partir do player — incluindo o atalho "Ler original" logo apos a conversao.
- Gerenciamento de biblioteca local de audiobooks (com busca por nome) e fila de conversao.
- Interface reorganizada em abas (Ajustes, menu de acoes do leitor, menu da tela principal), com titulo e descricao claros em cada opcao.

## Arquitetura

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/          Activities, adapters e view models (MainActivity, ReaderActivity, SettingsActivity, ...)
  service/     Servicos de reproducao/conversao (AudioPlayerService, GuidedReadingService, ConversionWorker, SleepTimerManager)
  tts/         Motor TTS offline (OnnxTtsEngine, OnnxSynthBridge, helpers de Supertonic)
  domain/      Casos de uso (PythonEngineUseCase, ponte com o Chaquopy, extracao/exportacao)
  player/      Abstracao do player de audio (MediaPlayer/ExoPlayer)
  data/        Banco Room, repositorios e preferencias seguras
  util/        Auxiliares (LanguageDetector, VoiceCatalog, logs de crash, ...)

app/src/main/python/
  audiobook_android.py         Entrada Python chamada pelo Kotlin
  config_android.py            Configuracao em tempo de execucao
  core_processor_android.py    Orquestracao de conversao
  tts.py                       Roteamento de motor (Edge/Gemini/ponte ONNX)
  text_processor.py            Extracao e limpeza de texto
  gemini_tts.py                Logica do cliente Gemini TTS
  audio_cache.py               Camada de retomada/cache de chunks gerados
```

As bibliotecas nativas ONNX e os modelos embarcados sao distribuidos no APK da release e nao ficam neste repositorio.

## Seguranca E Privacidade

- Chaves reais de API nao sao versionadas no repositorio.
- `.env.example` documenta placeholders para configuracao local.
- Chaves Gemini sao armazenadas no dispositivo com `EncryptedSharedPreferences` e migradas da chave antiga em `SharedPreferences` no primeiro acesso.
- O repositorio tem um workflow de higiene no GitHub Actions que bloqueia padroes comuns de secrets vazados.
- Audio gerado, livros importados, caches, chaves de release, contas de servico e arquivos locais de assinatura sao ignorados pelo Git.

## Stack Tecnica

- Kotlin
- Android Views, ViewBinding e Material Components
- WorkManager
- Foreground service e controles de notificacao de media
- Chaquopy com Python 3.11
- Bibliotecas Python: `edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`
- Gradle Kotlin DSL

## Requisitos

- Android Studio com JDK 17
- Android SDK 36
- Android 8.0 ou superior no dispositivo alvo
- Acesso a internet para motores TTS online
- Opcional: chave de API do Google AI Studio para Gemini TTS

## Execucao Local

```bash
git clone https://github.com/JonJonesBR/LylyReader-Android.git
cd LylyReader-Android
./gradlew :app:assembleDebug
```

Instale o APK debug em um dispositivo conectado:

```bash
./gradlew :app:installDebug
```

Para builds de release, crie um arquivo local `release.properties` com os dados de assinatura. Nao versione esse arquivo.

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=change-me-locally
keyAlias=release
keyPassword=change-me-locally
```

## Download

O APK publico mais recente esta disponivel na [pagina de Releases do GitHub](https://github.com/JonJonesBR/LylyReader-Android/releases/latest). Este repositorio atualmente gera a versao `1.8.0` do app.

## Roadmap

- Adicionar testes automatizados para extracao de documentos e casos extremos de arquivos compartilhados.
- Adicionar testes instrumentados para as principais telas de importacao e conversao.
- Melhorar a resiliencia de conversoes longas com cobertura mais profunda de WorkManager.
- Expandir os metadados da listagem da Play Store para ingles e espanhol.

## Créditos

O LylyReader se apoia no trabalho de vários projetos e comunidades de código aberto:

- **Supertone – Supertonic** (vozes neurais offline): https://github.com/supertone-inc/supertonic
- **Kyutai Labs – Pocket TTS** (vozes offline, CC BY 4.0) e o runtime **PocketTTS.cpp** de VolgaGerm (MIT): https://github.com/kyutai-labs/pocket-tts · https://github.com/VolgaGerm/PocketTTS.cpp
- **Kokoro** e **sherpa-onnx** da k2-fsa (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- **ONNX Runtime** da Microsoft (MIT): https://github.com/microsoft/onnxruntime
- **Vozes Piper** dos projetos rhasspy e da comunidade (cada voz tem sua própria licença): https://github.com/rhasspy/piper
- **MMS-TTS** da Meta (CC BY-NC 4.0, uso não comercial): https://huggingface.co/facebook/mms-tts-por
- **Chaquopy** – Python no Android (MIT): https://chaquo.com/chaquopy/
- **Vozes online da Microsoft Edge** (via o cliente não oficial `edge-tts`, LGPL-3.0): https://github.com/rany2/edge-tts
- **Fonte Literata** do The Literata Project (SIL OFL 1.1): https://github.com/googlefonts/literata
- **Projeto Gutenberg**, **Wikisource** e **Internet Archive**, as fontes de domínio público da busca de livros.
- Pacotes Python: `aiohttp`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `numpy`, `olefile`, `mobi`, `httpx`.
- Bibliotecas Android do Google e do AndroidX (Apache-2.0), e a linguagem Kotlin da JetBrains (Apache-2.0).

Os detalhes completos de atribuição dos recursos e modelos incluídos estão em [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Licenca

MIT. Veja [LICENSE](LICENSE).
