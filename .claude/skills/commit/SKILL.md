---
name: commit
description: Use SEMPRE antes de criar qualquer commit no tv-launcher. Sobe o versionName (semver), atualiza o changelog in-app e o README, roda build/lint e escreve a mensagem no padrão do repo.
---

# Commit no tv-launcher

Siga este checklist em ordem antes de cada commit.

## 1. Revisar e classificar
Rode `git status` e `git diff` e classifique a mudança:
- **patch** (1.14.0 → 1.14.1): correção, ajuste visual, texto ou comportamento pequeno.
- **minor** (1.14.1 → 1.15.0, zera o patch): funcionalidade ou opção nova.
- **major**: só se o usuário pedir.
- **Sem bump**: mudanças só em `.claude/`, `docs/`, `.github/` ou só no README, sem tocar o app.

## 2. Subir a versão
- Edite o default de `versionName` em `app/build.gradle.kts` (`System.getenv("VERSION_NAME") ?: "X.Y.Z"`).
- Não mexa em `versionCode`, que vem do CI (`github.run_number`).
- Cada commit tem um bump. Se o diff já contém o bump, não suba de novo.

## 3. Changelog in-app
Acrescente no **topo** de `changelog_text` em `app/src/main/res/values/strings.xml`:
`X.Y.Z\n<frase curta voltada ao usuário, terminada em ponto>\n\n`
Mantenha os escapes do XML (`\n`, `\'`, `&amp;`). Se o patch for puramente interno (refactor, build), pode pular, mas diga ao usuário que pulou.

## 4. README
Se a mudança altera algo descrito no `README.md` (recursos, opções, menus, serviços acessados ou privacidade), atualize a seção correspondente.

## 5. Strings e limpeza
- Todo texto visível vai em `res/values/strings.xml`, nunca escrito direto no código ou no layout.
- Remova strings, recursos e prefs que deixaram de ser usados.

## 6. Verificar
Rode `./gradlew assembleDebug lintDebug`. O CI roda `assembleRelease lintRelease`, então avisos de lint quebram o release. Só faça o commit se passar. Se falhar, mostre a saída ao usuário.

## 7. Commit
- Faça o commit direto na `main`, sem criar branch.
- Adicione os arquivos pelo nome com `git add <arquivos>`. Nunca adicione `keystore.properties`, `local.properties` ou `release.jks`.
- Título: `X.Y.Z: <resumo em português, minúsculo depois dos dois-pontos, sem ponto final>`. Esse título vira o texto do Release e aparece na tela de atualização do launcher, então escreva para o usuário final.
- Commit sem bump: título sem o prefixo de versão.
- Corpo opcional explicando o porquê.
- Termine com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## 8. Não publicar
**Nunca** crie tag `v*` nem dê push: a tag dispara o workflow que publica o Release, e os aparelhos baixam a atualização a partir dele. Ao final, informe a versão gerada e lembre que publicar é com o usuário (`git tag vX.Y.Z && git push --follow-tags`).
