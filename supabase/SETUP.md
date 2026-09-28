# Conta EPlay Web — configuração gratuita

A conta é opcional. Sem Supabase configurado, o EPlay continua funcionando
normalmente e mantém dados localmente no navegador.

## 1. Criar o projeto

1. Crie uma conta em https://supabase.com/
2. Crie um projeto no plano Free.
3. Abra o SQL Editor do projeto.
4. Cole todo o conteúdo de `supabase/schema.sql`.
5. Execute o script e confirme que as três tabelas foram criadas.

As tabelas são:
- `user_preferences`
- `watch_history`
- `watch_progress`

Nenhuma delas armazena vídeos ou o catálogo Xtream.

## 2. Configurar autenticação

No painel Supabase, abra Authentication.
Mantenha o provedor Email habilitado.

Para confirmação por email, configure a URL pública do EPlay
como URL do site publicado no GitHub Pages.

No Authentication → URL Configuration, mantenha como Site URL e Redirect URL permitida:
`https://andsouzam.github.io/AndPlay/`

Essa mesma URL é usada pelo fluxo de recuperação de senha.
O usuário escolhe “Esqueci minha senha”, recebe um email e volta para a Home,
onde o EPlay abre a etapa para definir a nova senha.

O envio de email precisa estar habilitado no provedor Email do Supabase.
O serviço padrão de email é adequado para testes, mas possui limite de envio;
para uso público maior, configure SMTP próprio no Supabase.

## 3. Copiar os dados públicos da API

Em Project Settings → API, copie:
- Project URL
- Publishable key

Use a chave **publishable**, nunca uma secret/service_role key.

Edite:
`assets/js/supabase-config.js`

e preencha:

```js
window.ANDPLAY_SUPABASE_CONFIG = {
  url: 'https://SEU-PROJETO.supabase.co',
  publishableKey: 'SUA_CHAVE_PUBLICAVEL'
};
```

Depois publique os arquivos normalmente pelo GitHub Pages.

## 4. O que é sincronizado

A conta sincroniza:
- Assistidos
- Continuar assistindo
- Pular abertura automaticamente
- Estado da ficha técnica do player
- outros campos de preferência adicionados futuramente

O servidor Xtream continua sendo a origem do catálogo e dos vídeos.

## 5. Custo

Esta integração foi desenhada para funcionar no Supabase Free.
O código não usa Storage para vídeos, Edge Functions ou processamento de mídia.

Consulte os limites atuais do plano Free antes de colocar a aplicação
em uso amplo.
