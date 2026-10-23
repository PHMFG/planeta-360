# Planeta 360 — Portal do Patrocinador

Projeto de demonstração do portal de parcerias do Planeta Atlântida.

## O que está nesta pasta

| Local | Função |
|---|---|
| `frontend/index.html` | Interface HTML visual que foi enviada (protótipo independente) |
| `src/main/resources/static/index.html` | Cópia da interface HTML servida diretamente pelo Java |
| `frontend/src/` | Interface React + TypeScript conectada à API |
| `frontend/app-react.html` | Entrada da interface React para compilação |
| `frontend/public/assets/` | Logo e imagens usadas no front-end |
| `src/main/java/` | API Java/Spring Boot: autenticação, permissões, materiais e relatórios |
| `src/main/resources/application.yml` | Configurações da aplicação e do banco |
| `database/` | Scripts de criação e carga do PostgreSQL (executar só se necessário) |
| `atualizar-front-end.ps1` | Recompila React e publica as duas páginas no Java |
| `pom.xml` | Dependências Maven |

**Importante:** A página `/` é o HTML demonstrativo e guarda estado apenas no navegador. Ela **não** salva as alterações no PostgreSQL nem faz login pela API. A página `/app-react.html` (disponível depois de compilar o front-end) é a interface React que utiliza o back-end. Não use o protótipo como prova de integração com a API ou permissões do banco.

## 1. Pré-requisitos

- JDK 21;
- PostgreSQL (no computador em que o projeto foi montado, PostgreSQL 18 na porta 5433);
- Apache Maven;
- Node.js e npm **somente para reconstruir** a interface React.

O banco `portal` e o usuário `portal` já foram criados durante a configuração anterior. **Se você está usando esse mesmo computador/banco, não precisa rodar os scripts SQL outra vez.**

## 2. Rodar o HTML novo pelo Spring Boot (caminho mais simples)

Abra o VS Code na pasta que contém **este** `README.md` e o `pom.xml`.
No terminal **PowerShell**, execute um comando por vez:

```powershell
Test-Path .\pom.xml
Test-NetConnection localhost -Port 5433
$env:DB_URL = 'jdbc:postgresql://localhost:5433/portal'
$env:DB_USER = 'portal'
$env:DB_PASSWORD = 'portal'
$env:DEV_SEED = 'true'
```

O primeiro teste deve dizer `True` e o segundo deve mostrar `TcpTestSucceeded : True`.
A senha `portal` só funciona caso tenha sido cadastrada assim no PostgreSQL. Utilize a sua senha, se for diferente.

Se `mvn` estiver disponível no terminal:

```powershell
mvn spring-boot:run
```

Caso `mvn` não seja encontrado, no computador usado neste projeto o executável está em:

```powershell
& 'C:\Users\voce\Downloads\apache-maven-3.10.0-bin\apache-maven-3.10.0\bin\mvn.cmd' spring-boot:run
```

Abra **http://localhost:8080/**. Mantenha o terminal aberto. Para parar o servidor, use **Ctrl+C**.

> Com a pasta limpa, o HTML novo já está incluído em `src/main/resources/static/index.html`, então pode abrir a página principal sem instalar dependências npm. A API Java, porém, ainda precisa de PostgreSQL funcionando.

## 3. Atualizar o front-end e gerar a página React

Na pasta do projeto, execute:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\atualizar-front-end.ps1'
```

O script instala os pacotes npm caso estejam ausentes, compila o React/TypeScript e atualiza os arquivos estáticos do Spring Boot. Ele não recria o banco. Para conferir:

- **http://localhost:8080/** — HTML demonstrativo enviado;
- **http://localhost:8080/app-react.html** — interface React com chamadas à API.

Se já havia iniciado o Java, reinicie-o após a compilação e atualize o navegador com **Ctrl+F5**.

## 4. Contas da interface React de demonstração

Essas contas exigem o banco com a carga de demonstração e `DEV_SEED=true`:

| Perfil | E-mail | Senha de teste |
|---|---|---|
| Gestão RBS | `b2b@rbs.example` | `senha123` |
| Patrocinador Amarelo | `marketing@amarelo.example` | `senha123` |
| Patrocinador Roxo | `marketing@roxo.example` | `senha123` |

Não use essas senhas nem a configuração de JWT de demonstração em produção.

## 5. O que foi removido do ZIP

- `frontend/node_modules/`: bibliotecas que `npm ci` reinstala;
- `frontend/dist/`: arquivos gerados pela compilação;
- `target/`: arquivos gerados pelo Maven;
- pastas vazias e arquivos de ferramentas externas que não pertencem ao projeto;
- `frontend/tsconfig.tsbuildinfo`: cache do TypeScript;
- versões estáticas antigas dos bundles CSS/JS.

Foi preservado o código-fonte necessário para a API, o banco, o HTML novo e a interface React.

## 6. Observações

- `src/main/` é **obrigatória** para o Spring Boot. Não apague a pasta `src`.
- A aba "Hoje"/"Ontem" do Explorador agrupa arquivos por data; não faz parte da estrutura do projeto.
- Se você editar `frontend/index.html`, rode o script de atualização para refletir no `src/main/resources/static/index.html`, ou copie apenas esse HTML para a pasta `static`.
- A publicação do repositório no GitHub não publica automaticamente a aplicação em um servidor na internet.
