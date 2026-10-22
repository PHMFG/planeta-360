# Front-end Planeta 360 — versão visual aprimorada

Esta pasta contém a interface React + TypeScript do Portal Planeta 360, redesenhada para ficar mais próxima do mockup `Mockup.pdf` enviado no projeto.

## O que mudou

- Login corporativo com identidade azul do Planeta 360 e explicação de acesso.
- Interface com menu lateral, cabeçalhos diagonais e painéis mais próximos do mockup.
- Cores por seção: Minha parceria `#073C89`, Perfil `#0A7D91`, Aplicações da marca `#00862E`, Mídia e conteúdo `#A36B00`, Cortesias `#CF4000`, Credenciamento `#D62F57`, Pesquisa `#E1047D` e Contrato `#572781`.
- Visão de patrocinador e visão de gestão RBS separadas pelo perfil retornado pela API após o login. O usuário não escolhe o tipo de conta na tela: o backend decide com base no perfil cadastrado para aquele e-mail.
- Gestão RBS com fila de entregas, atualização de status e cadastro de e-mails autorizados para a parceria selecionada.
- Atlas em painel lateral com orientações demonstrativas.
- Layout adaptável a telas menores.

## Como instalar esta pasta no projeto existente

1. Faça uma cópia de segurança da pasta `frontend` atual.
2. Extraia/substitua a pasta `frontend` do projeto pela pasta deste pacote.
3. Abra o Prompt de Comando dentro de `frontend` e execute:

   ```cmd
   npm install
   npm run build
   ```

4. Volte para a pasta principal do projeto e copie o build para os arquivos estáticos do Spring Boot:

   ```cmd
   cd ..
   if not exist src\main\resources\static mkdir src\main\resources\static
   xcopy /E /I /Y frontend\dist\* src\main\resources\static\
   ```

5. Inicie o backend conforme o `README.md` principal do projeto.

## Contas de demonstração

Com `DEV_SEED=true`, use a senha `senha123`:

- Gestão RBS: `b2b@rbs.example`
- Patrocinador: `marketing@amarelo.example`
- Outro patrocinador de demonstração: `marketing@roxo.example`

A separação de áreas depende do perfil autorizado retornado pelo backend; trocar o texto do e-mail por si só não concede permissões.

## Nota sobre o escopo

As telas de gestão de entregas, autorização de e-mails, login, envio de arquivos, histórico e relatórios conectam-se às rotas já existentes do backend. Algumas áreas do mockup — por exemplo, publicação/visualização do contrato oficial, integrações de cortesias e Open Staff e fluxo completo de perguntas da pesquisa — continuam identificadas como dependentes de integração ou de conteúdo publicado pela RBS; a interface não inventa esses dados.
