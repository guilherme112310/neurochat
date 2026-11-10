# NeuroChat+

Rede social para pessoas neurodivergentes. App Android (WebView) com a interface em um único arquivo React.

## O que tem
- Feed, grupos, chat e perfis com os transtornos escolhidos
- Terapia online (agenda com profissionais)
- Testes "será que você tem?" (20 perguntas, índice de compatibilidade, **não é diagnóstico**)
- Jogos com 4 níveis, estrelas e conquistas, e jogos de relaxar
- Tema escuro/claro e layout responsivo (celular, tablet e computador)

## Onde fica o código
- Interface: `app/src/main/assets/index.html`
- Android: `app/src/main/java/com/neurochat/app/MainActivity.java`

## Como gerar o APK
1. Abra a pasta no Android Studio.
2. Coloque o seu `google-services.json` em `app/` (não vai para o GitHub, está no `.gitignore`).
3. Build > Build APK(s).

## Aviso
Os testes e jogos são para autoconhecimento e bem-estar. Não substituem avaliação de um profissional de saúde.
