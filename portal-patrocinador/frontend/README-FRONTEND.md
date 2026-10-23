# Front-end Planeta 360 — protótipo HTML integrado

A página principal agora usa o HTML `frontend/index.html` enviado para o projeto. Ele reúne a interface visual do protótipo Planeta 360 em um único arquivo, com CSS, fontes, ícones e JavaScript embutidos.

## Páginas disponíveis

- **`/`** — protótipo HTML enviado (`frontend/index.html`). As interações e os dados de demonstração são locais ao navegador; esta página ainda não envia nem consulta dados pela API Java.
- **`/app-react.html`** — interface React/TypeScript anterior, preservada como página alternativa e conectada às rotas da API existentes.

## Compilar e publicar

Na pasta `frontend`, execute:

```cmd
npm install
npm run build
```

O Vite gera as duas páginas em `frontend/dist`. Para copiá-las para o Spring Boot, volte à pasta principal e execute:

```powershell
powershell -ExecutionPolicy Bypass -File .\atualizar-front-end.ps1
```

Depois inicie ou reinicie o back-end. A página principal fica em `http://localhost:8080/`; a interface React anterior fica em `http://localhost:8080/app-react.html`.

## Observação sobre integração

O HTML principal é um protótipo visual autônomo e utiliza armazenamento local do navegador. Login, permissões, entregas, arquivos e dados persistidos no PostgreSQL continuam disponíveis na interface React alternativa. Para que a página HTML principal use esses mesmos dados, suas ações precisam ser ligadas aos endpoints da API.
