# Planeta 360 — Portal do Patrocinador

## 1. Sobre o projeto

O Planeta 360 — Portal do Patrocinador é uma aplicação web desenvolvida para centralizar informações, documentos, entregas e atividades relacionadas às parcerias do Planeta Atlântida.

O sistema possui dois perfis de acesso distintos:

- **Patrocinador:** acessa as informações e funcionalidades relacionadas exclusivamente à sua própria parceria.
- **Gestão RBS:** acessa a área administrativa para acompanhar entregas, gerenciar parcerias e consultar informações conforme as permissões do perfil.

Cada usuário deve utilizar o e-mail correspondente ao seu perfil. A interface e as permissões disponíveis dependem da autenticação e das regras de autorização implementadas no sistema.

## 2. Tecnologias utilizadas

| Camada | Tecnologia |
|---|---|
| Front-end | React |
| Linguagem do front-end | TypeScript |
| Estilização | Tailwind CSS |
| Back-end | Java 21 |
| Framework do back-end | Spring Boot |
| Banco de dados | PostgreSQL |
| Gerenciamento do back-end | Apache Maven |
| Gerenciamento do front-end | npm |

## 3. Perfis de acesso

### 3.1. Patrocinador — área da parceria

O perfil de patrocinador é destinado às empresas que possuem uma parceria com o evento. O usuário utiliza o e-mail autorizado para consultar as informações da própria empresa.

As funcionalidades previstas incluem:

- Consulta das informações da parceria e da cota contratada.
- Acompanhamento de entregas e pendências.
- Envio e consulta de materiais da marca.
- Consulta de documentos disponibilizados pela RBS.
- Acesso às funcionalidades habilitadas para a respectiva parceria.

Contas de demonstração:

| Empresa | E-mail | Senha |
|---|---|---|
| Patrocinador Amarelo | `marketing@amarelo.example` | `senha123` |
| Patrocinador Roxo | `marketing@roxo.example` | `senha123` |

Cada conta representa uma parceria independente. O patrocinador Amarelo deve utilizar o e-mail correspondente à sua conta, assim como o patrocinador Roxo deve utilizar o seu próprio e-mail.

O patrocinador não possui acesso automático às funcionalidades administrativas da RBS.

### 3.2. Gestão RBS — área administrativa

O perfil de gestão RBS é destinado à equipe responsável pela administração e pelo acompanhamento operacional das parcerias.

As funcionalidades previstas incluem:

- Consulta e acompanhamento das entregas dos patrocinadores.
- Acompanhamento de materiais recebidos e pendências.
- Gerenciamento de informações das empresas, cotas e acessos, conforme as permissões disponíveis.
- Consulta dos status das entregas.
- Preparação e disponibilização de informações e documentos relacionados às parcerias.

Conta de demonstração:

| Perfil | E-mail | Senha |
|---|---|---|
| Gestão RBS | `b2b@rbs.example` | `senha123` |

O e-mail `b2b@rbs.example` corresponde ao perfil administrativo da RBS. Ele não deve ser confundido com os e-mails utilizados pelos patrocinadores.

**Importante:** as contas e senhas apresentadas nesta documentação são destinadas ao ambiente local de demonstração. A separação efetiva de permissões depende da configuração de autenticação e autorização do back-end. A simples alteração do e-mail exibido na interface não deve conceder acesso administrativo.

## 4. Pré-requisitos

Antes de executar o projeto, instale os seguintes componentes:

1. Java JDK 21.
2. Node.js, incluindo o npm.
3. PostgreSQL.
4. Apache Maven.

Após a instalação, abra o Prompt de Comando do Windows e verifique se as ferramentas estão disponíveis:

```cmd
java -version
node -v
npm -v
mvn -version
```

Cada comando deve apresentar a versão correspondente.

### Configuração do Maven

Se o Maven estiver instalado, mas o comando `mvn` não for reconhecido, configure o caminho da pasta `bin` da instalação.

Exemplo utilizado no ambiente de desenvolvimento:

```cmd
set "PATH=C:\Users\voce\Downloads\apache-maven-3.10.0-bin\apache-maven-3.10.0\bin;%PATH%"
mvn -version
```

O caminho acima é específico do computador em que o projeto foi configurado. Em outro ambiente, substitua-o pelo caminho real da instalação do Maven.

Essa configuração temporária vale apenas para a janela atual do Prompt de Comando.

## 5. Como executar o projeto no Windows

### 5.1. Abrir a pasta do projeto

Extraia os arquivos do projeto e localize a pasta principal `portal-patrocinador`, que contém o arquivo `pom.xml`, a pasta `frontend` e a pasta `database`.

Para abrir o Prompt de Comando diretamente nessa pasta:

1. Abra a pasta no Explorador de Arquivos.
2. Clique na barra de endereço.
3. Digite `cmd`.
4. Pressione Enter.

Caso necessário, entre na pasta manualmente:

```cmd
cd /d "C:\CAMINHO\portal-patrocinador"
```

Substitua o caminho pelo local em que o projeto foi extraído.

### 5.2. Instalar as dependências e compilar o front-end

Na pasta principal do projeto, execute:

```cmd
cd frontend
npm install
npm run build
cd ..
```

A compilação gera os arquivos estáticos do front-end na pasta `frontend\dist`.

Em seguida, copie os arquivos gerados para a pasta estática utilizada pelo Spring Boot:

```cmd
if not exist src\main\resources\static mkdir src\main\resources\static
xcopy /E /I /Y frontend\dist\* src\main\resources\static\
```

A operação deve copiar o arquivo `index.html` e os arquivos de recursos da pasta `assets`.

Essa etapa deve ser repetida sempre que o front-end for atualizado e precisar ser servido pelo back-end Java.

### 5.3. Configurar o PostgreSQL

A configuração utilizada durante a preparação do ambiente local foi:

| Parâmetro | Valor |
|---|---|
| Banco de dados | `portal` |
| Usuário da aplicação | `portal` |
| Senha da aplicação | `portal` |
| Servidor | `localhost` |
| Porta | `5433` |
| Versão utilizada no teste | PostgreSQL 18.6 |

A porta pode variar de acordo com a instalação do PostgreSQL. Caso seja diferente, ajuste os comandos SQL e a URL de conexão utilizados na inicialização da aplicação.

Na primeira configuração, os scripts SQL da pasta `database` devem ser executados na seguinte ordem:

1. `00_criar_banco.sql` — cria o usuário e o banco de dados.
2. `01_schema.sql` — cria as tabelas e os índices.
3. `02_logica.sql` — configura as funções e visões do banco.
4. `03_amostra.sql` — carrega os dados de demonstração.
5. `04_cargas.sql` — executa as cargas de dados previstas no projeto.

Os scripts devem ser executados conforme as dependências entre eles e as instruções de cada arquivo.

**Atenção:** execute os scripts de inicialização apenas quando necessário. Não os execute novamente sobre um banco já configurado sem verificar seus efeitos, pois objetos existentes podem gerar conflitos ou dados podem ser alterados.

A senha administrativa do usuário `postgres` é independente da senha do usuário de aplicação `portal`.

### 5.4. Iniciar o back-end

Na pasta principal do projeto, configure as variáveis de ambiente e inicie o Spring Boot:

```cmd
set "DB_URL=jdbc:postgresql://localhost:5433/portal"
set "DB_USER=portal"
set "DB_PASSWORD=portal"
set "DEV_SEED=true"
mvn spring-boot:run
```

Se o PostgreSQL estiver utilizando outra porta, ajuste a variável `DB_URL`.

Mantenha a janela do Prompt de Comando aberta enquanto estiver utilizando o portal. Na primeira execução, o Maven poderá precisar de alguns minutos para baixar as dependências.

### 5.5. Acessar a aplicação

Após a inicialização bem-sucedida do Spring Boot, abra o navegador e acesse:

[http://localhost:8080/](http://localhost:8080/)

Utilize uma das contas de demonstração descritas na seção de perfis de acesso.

Para encerrar a aplicação, retorne à janela do Prompt de Comando e pressione `Ctrl + C`.

## 6. Estrutura do projeto

```text
portal-patrocinador/
├── frontend/       # Interface React e TypeScript
├── src/            # Aplicação Java e arquivos estáticos
├── database/       # Scripts SQL e dados de demonstração
├── pom.xml         # Configuração e dependências do Maven
└── README.md       # Documentação do projeto
```

## 7. Solução de problemas

### O comando `mvn` não é reconhecido

Verifique se o Apache Maven foi extraído corretamente e se a pasta `bin` está incluída no `PATH` da janela atual do Prompt de Comando.

### O comando `node` ou `npm` não é reconhecido

Verifique a instalação do Node.js e abra uma nova janela do Prompt de Comando após a instalação.

### O front-end não compila

Verifique se as dependências foram instaladas e se os comandos foram executados dentro da pasta `frontend`.

```cmd
npm install
npm run build
```

### O site não abre no navegador

Confirme se o Spring Boot iniciou sem erros e se a porta `8080` está disponível.

### A aplicação não conecta ao banco de dados

Verifique se o serviço PostgreSQL está ativo, se a porta configurada está correta e se as credenciais correspondem ao usuário de aplicação.

### O login não funciona

Confirme que está utilizando o e-mail correspondente ao perfil desejado e que os dados de demonstração foram carregados corretamente.

O acesso de patrocinador e o acesso da gestão RBS são distintos. Utilize o e-mail administrativo somente para testar a área RBS.

## 8. Segurança e ambiente de demonstração

As credenciais documentadas neste arquivo são destinadas exclusivamente ao ambiente local de demonstração.

Antes de disponibilizar o sistema em produção, é necessário:

- Configurar credenciais próprias e seguras.
- Revisar as regras de autenticação e autorização.
- Garantir o isolamento dos dados de cada patrocinador.
- Proteger as informações sensíveis e as configurações de conexão.
- Validar as permissões administrativas no back-end.
- Desabilitar configurações e dados exclusivos de demonstração quando apropriado.

## 9. Considerações finais

O Planeta 360 — Portal do Patrocinador centraliza as informações das parcerias em uma única aplicação, com áreas destinadas aos patrocinadores e à equipe de gestão RBS.

A documentação descreve a configuração utilizada no ambiente local de demonstração. O funcionamento de cada recurso depende da implementação disponível no código, das permissões configuradas e dos dados existentes no banco de dados.