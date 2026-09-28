# EPlay — Contexto do projeto

## Objetivo
EPlay é a interface web estática de filmes, séries e TV ao vivo hospedada no GitHub Pages.
O projeto também contém um aplicativo Android, mas Web e Android são tratados como projetos independentes.

## Regra crítica
Para tarefas da interface Web, NUNCA alterar arquivos dentro de `android/` nem recriar/modificar o APK.
O APK pode ser consultado apenas como referência de comportamento ou design.

## Repositório
- Pasta local: `C:\Users\Anderson\AndPlay`
- Branch principal: `main`
- Remote: `https://github.com/andsouzam/AndPlay.git`
- Publicação: GitHub Pages
- Site: `https://andsouzam.github.io/AndPlay/`
- Produto/marca visível: **EPlay**

## Arquitetura Web
- `index.html` — estrutura da interface e modo TV.
- `assets/css/app.css` — estilos Web, Home e modo TV.
- `assets/js/app.js` — catálogo, reprodução, Home, histórico, progresso, skip intro e modo TV.
- `assets/js/account.js` — autenticação Supabase, sessão e sincronização.
- `assets/js/public-config.js` — configuração pública Xtream.
- `assets/js/supabase-config.js` — URL e publishable key do Supabase.
- `404.html` — fallback do GitHub Pages.
- `supabase/schema.sql` — tabelas/RLS da conta.
- `supabase/SETUP.md` — configuração do Supabase.

## Credenciais públicas
As credenciais Xtream do Web já são públicas por desenho e ficam em `public-config.js`.
O publishable key do Supabase pode ficar no browser; NUNCA colocar `service_role`/secret key no código Web.
## Supabase / Conta
O projeto Supabase usado pela Web é `https://zfawwhqogtynuygniskz.supabase.co`.
A conta é opcional: sem login, o EPlay continua funcionando com armazenamento local.

Recursos atuais da conta:
- email + senha;
- login com Google via Supabase OAuth;
- sincronização de histórico, progresso e preferências;
- preferências de skip intro;
- perfil de gosto da Home;
- recuperação de senha por email.

### Recuperação de senha
O fluxo usa `supabase.auth.resetPasswordForEmail()` e, depois do link recebido, `supabase.auth.updateUser({ password })`.
O retorno usa a própria página `https://andsouzam.github.io/AndPlay/`.
No Supabase Authentication → URL Configuration, essa URL precisa estar na Redirect URL Allow List.
O envio de email depende do serviço de email do Supabase ou de SMTP configurado; o serviço padrão possui limitação de envio e é indicado apenas para testes.

### Google OAuth
Callback do Supabase: `https://zfawwhqogtynuygniskz.supabase.co/auth/v1/callback`.
No Google Cloud, a origem do site é `https://andsouzam.github.io` e o callback acima é usado como redirect URI.

## Banco da conta
As principais tabelas são:
- `user_preferences` — preferências e perfil de uso;
- `watch_history` — filmes/séries assistidos, por usuário;
- `watch_progress` — posição de reprodução VOD.
Todas usam RLS por usuário.

## Compatibilidade de dados
Chaves `localStorage` que começam com `andplay_` fazem parte do formato interno legado e NÃO devem ser renomeadas apenas por causa da troca de marca para EPlay.
Isso preserva histórico, progresso, cache, posters, preferências e dados já existentes no navegador.
## Home atual
A Home é a central de descoberta do EPlay.
1. Banner superior com novidades em carrossel.
2. `Últimos assistidos`, misturando filmes e séries.
3. `Para você`, criado a partir do perfil de gosto do usuário quando há histórico suficiente.
4. Seção `Séries`, com novidades e trilhos por temas.
5. Seção `Filmes`, com novidades e trilhos por temas.

O perfil de gosto usa os gêneros dos títulos realmente iniciados pelo usuário.
Temas preferidos ganham peso, mas o trilho personalizado reserva espaço para descoberta fora dos principais temas.
Títulos já assistidos são excluídos do trilho `Para você`.

Os temas combinam metadados do catálogo com gêneros disponíveis no cache externo do Cinemeta quando existe IMDb ID.
A avaliação IMDb é lida como `imdbRating` do Cinemeta e armazenada em cache por 7 dias.
Quando a nota IMDb não está disponível, a Home pode usar a nota original do catálogo, identificada como `Catálogo`.

## Carrosséis da Home
As barras horizontais ficam visualmente ocultas.
Os trilhos usam navegação por seta e mantêm uma parte do próximo card visível para indicar conteúdo adicional.
Há regras específicas para desktop, tablet e celular.
As setas são ocultadas quando não existe conteúdo adicional.

## Cache e desempenho
O catálogo Web usa IndexedDB com cache persistente.
Filmes e séries têm TTL de aproximadamente 6 horas; TV ao vivo usa TTL de aproximadamente 2 minutos.
A Home reaproveita esse cache e enriquece apenas uma quantidade limitada de títulos com dados IMDb.

## Reprodução e histórico
- histórico local de filmes e séries;
- progresso VOD salvo localmente e sincronizado quando há conta;
- retomada da reprodução próxima da posição salva;
- conclusão limpa o progresso;
- skip intro usa timestamps comunitários quando disponíveis;
- HLS é usado para streams `.m3u8`.
## Histórico recente de implementação
- `02312f8` — arquitetura Web separada, Home/cache e conta.
- `8d35eca` — conexão da conta Web ao Supabase.
- `d75469f` — correção do tipo de conteúdo no histórico Supabase.
- `6c18dd9` — login com Google.
- `39aa49a` — Home como vitrine de novidades.
- `cff9ba4` — redução da altura do banner em telas menores.
- `a6c8eb7` — Séries/Filmes e temas na Home.
- `b347a8c` — personalização por gosto e carrosséis sem scrollbar.
- `39dfe33` — correção do peek dos carrosséis.

## Validação recomendada
Depois de alterações Web:
1. `node --check assets\js\app.js`
2. `node --check assets\js\account.js`
3. `git diff --check`
4. verificar `git status --short`.
5. quando houver mudanças visuais importantes, testar a Home no navegador.

## Continuidade em um novo chat
Antes de modificar o projeto em uma nova conversa:
1. ler este arquivo `PROJECT_CONTEXT.md`;
2. executar `git status --short` e `git log --oneline -10`;
3. revisar apenas os arquivos ligados à tarefa;
4. confirmar que `android/` continua intocado para tarefas Web.

Não assumir que o histórico da conversa está disponível. Este arquivo é a fonte de contexto operacional do projeto.

## Observações importantes
O nome da pasta/repositório ainda é `AndPlay`; isso é esperado e não precisa ser renomeado para alterar a marca exibida para EPlay.
Classes CSS, chaves de localStorage, IDs internos e nomes JavaScript históricos com `andplay` também podem permanecer para compatibilidade.
A marca que aparece para o usuário deve ser **EPlay**.

Quando adicionar novos recursos de conta, preferir as APIs oficiais atuais do Supabase Auth e manter qualquer chave secreta fora do navegador.
## Reset de senha — implementação atual
O modal de conta agora possui `Esqueci minha senha`.
O formulário solicita o email e chama `auth.resetPasswordForEmail()` com redirect para a própria Home.
A mensagem após o pedido é deliberadamente genérica para não revelar se um email existe no sistema.
Ao retornar pelo link, o evento Auth `PASSWORD_RECOVERY` abre automaticamente a etapa `LINK DE RECUPERAÇÃO`.
A nova senha é confirmada no browser e aplicada com `auth.updateUser({ password })`.

Para o fluxo funcionar em produção, conferir no Supabase:
- Authentication → Providers → Email habilitado;
- Authentication → URL Configuration → `https://andsouzam.github.io/AndPlay/` permitida como redirect;
- serviço de email/SMTP operacional.

## Estado de marca
A marca pública é **EPlay**.
A pasta do repositório continua se chamando `AndPlay` por compatibilidade.
As chaves e nomes internos `andplay_*`, `AndPlayAccount`, `ANDPLAY_SUPABASE_CONFIG` etc. permanecem por compatibilidade técnica e não representam texto da interface.

## Não perder em futuras alterações
Não substituir silenciosamente as chaves de localStorage existentes, não mover a publicação para outro servidor sem decisão explícita e não tocar no APK para resolver problemas Web.
