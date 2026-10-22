-- Cria o usuário e o banco "portal" se ainda não existirem (rodar como superusuário "postgres").
SELECT 'CREATE ROLE portal LOGIN PASSWORD ''portal'''
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'portal')\gexec
SELECT 'CREATE DATABASE portal OWNER portal'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'portal')\gexec
