-- ============================================================
--  SORTE OURO · Banco Administrativo (PostgreSQL)
--  Banco separado do banco principal de apostas
--  Execute como superusuário:  psql -U postgres -f admin_schema.sql
-- ============================================================

-- 1. Criar banco e usuário dedicados
CREATE DATABASE sorte_admin
    WITH OWNER = postgres
    ENCODING = 'UTF8'
    LC_COLLATE = 'pt_BR.UTF-8'
    LC_CTYPE   = 'pt_BR.UTF-8'
    TEMPLATE = template0;

CREATE USER admin_user WITH ENCRYPTED PASSWORD 'TROQUE_AQUI';
GRANT ALL PRIVILEGES ON DATABASE sorte_admin TO admin_user;

-- Conecte ao banco recém-criado antes de criar as tabelas:
-- \c sorte_admin

-- ============================================================
--  EXTENSÕES
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
--  ADMINISTRADORES (login separado dos vendedores)
-- ============================================================
CREATE TABLE IF NOT EXISTS admin_users (
    id            SERIAL PRIMARY KEY,
    uuid          UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(180) UNIQUE,
    phone         VARCHAR(20) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,           -- bcrypt
    role          VARCHAR(30) NOT NULL DEFAULT 'admin',  -- admin | supervisor | financeiro
    region_id     INTEGER,                          -- NULL = acessa todas as regiões
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_login    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
--  SESSÕES / TOKENS JWT admin
-- ============================================================
CREATE TABLE IF NOT EXISTS admin_sessions (
    id          SERIAL PRIMARY KEY,
    admin_id    INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token_jti   VARCHAR(64) UNIQUE NOT NULL,       -- JWT jti claim
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(300),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_sessions_admin ON admin_sessions(admin_id);
CREATE INDEX idx_admin_sessions_jti   ON admin_sessions(token_jti);

-- ============================================================
--  AUDITORIA (log de todas as ações do admin)
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    admin_id    INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    admin_name  VARCHAR(120),                      -- snapshot do nome
    action      VARCHAR(80) NOT NULL,              -- ex: 'bet.cancel', 'user.create'
    target_type VARCHAR(60),                       -- 'bet' | 'user' | 'draw' | 'config'
    target_id   VARCHAR(40),                       -- ID do objeto afetado
    detail      JSONB,                             -- payload completo da ação
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_admin    ON audit_log(admin_id);
CREATE INDEX idx_audit_action   ON audit_log(action);
CREATE INDEX idx_audit_date     ON audit_log(created_at DESC);
CREATE INDEX idx_audit_target   ON audit_log(target_type, target_id);

-- ============================================================
--  CACHE DE RELATÓRIOS (evita recalcular a cada acesso)
-- ============================================================
CREATE TABLE IF NOT EXISTS report_cache (
    id          SERIAL PRIMARY KEY,
    report_key  VARCHAR(120) UNIQUE NOT NULL,      -- ex: 'daily:2026-07-25:region:1'
    data        JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '1 hour'
);
CREATE INDEX idx_report_key     ON report_cache(report_key);
CREATE INDEX idx_report_expires ON report_cache(expires_at);

-- ============================================================
--  RESUMO FINANCEIRO DIÁRIO (snapshot persistido)
-- ============================================================
CREATE TABLE IF NOT EXISTS financial_summary (
    id              SERIAL PRIMARY KEY,
    date            DATE NOT NULL,
    region_id       INTEGER,
    total_bets      INTEGER NOT NULL DEFAULT 0,
    total_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,   -- arrecadado bruto
    total_prizes    NUMERIC(14,2) NOT NULL DEFAULT 0,   -- prêmios pagos
    total_cancelled NUMERIC(14,2) NOT NULL DEFAULT 0,
    net_revenue     NUMERIC(14,2) GENERATED ALWAYS AS (total_amount - total_prizes - total_cancelled) STORED,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(date, region_id)
);
CREATE INDEX idx_fin_date   ON financial_summary(date DESC);
CREATE INDEX idx_fin_region ON financial_summary(region_id);

-- ============================================================
--  POR MODALIDADE (agrupamento diário)
-- ============================================================
CREATE TABLE IF NOT EXISTS financial_by_modality (
    id           SERIAL PRIMARY KEY,
    date         DATE NOT NULL,
    region_id    INTEGER,
    bet_type     VARCHAR(60) NOT NULL,
    total_bets   INTEGER NOT NULL DEFAULT 0,
    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_prizes NUMERIC(14,2) NOT NULL DEFAULT 0,
    UNIQUE(date, region_id, bet_type)
);

-- ============================================================
--  CONFIGURAÇÕES DO SISTEMA
-- ============================================================
CREATE TABLE IF NOT EXISTS system_config (
    key         VARCHAR(80) PRIMARY KEY,
    value       TEXT NOT NULL,
    description VARCHAR(255),
    updated_by  INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Valores padrão
INSERT INTO system_config (key, value, description) VALUES
  ('max_bet_per_number',  '500.00',  'Valor máximo por número (R$)'),
  ('min_bet_amount',      '0.10',    'Valor mínimo por aposta (R$)'),
  ('ticket_validity_days','15',      'Validade do bilhete em dias'),
  ('commission_rate',     '0.30',    'Taxa de comissão do vendedor (0-1)'),
  ('system_name',         'Sorte Ouro', 'Nome do sistema exibido nos bilhetes')
ON CONFLICT (key) DO NOTHING;

-- ============================================================
--  NOTIFICAÇÕES INTERNAS
-- ============================================================
CREATE TABLE IF NOT EXISTS admin_notifications (
    id          SERIAL PRIMARY KEY,
    admin_id    INTEGER REFERENCES admin_users(id) ON DELETE CASCADE,
    title       VARCHAR(120) NOT NULL,
    body        TEXT,
    type        VARCHAR(30) DEFAULT 'info',        -- info | warning | error
    read        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notif_admin ON admin_notifications(admin_id, read);

-- ============================================================
--  TRIGGER: updated_at automático
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;

CREATE TRIGGER trg_admin_users_upd
    BEFORE UPDATE ON admin_users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
--  ADMIN PADRÃO (altere a senha antes de ir para produção!)
-- ============================================================
INSERT INTO admin_users (name, phone, password_hash, role)
VALUES (
    'Administrador',
    '83000000000',
    crypt('admin123', gen_salt('bf', 12)),  -- TROQUE A SENHA
    'admin'
) ON CONFLICT DO NOTHING;

-- ============================================================
--  VIEWS ÚTEIS
-- ============================================================

-- Resumo da semana atual
CREATE OR REPLACE VIEW vw_weekly_summary AS
SELECT
    date,
    SUM(total_amount)  AS arrecadado,
    SUM(total_prizes)  AS premios,
    SUM(net_revenue)   AS lucro,
    SUM(total_bets)    AS apostas
FROM financial_summary
WHERE date >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY date
ORDER BY date DESC;

-- Log de auditoria resumido
CREATE OR REPLACE VIEW vw_recent_audit AS
SELECT
    al.id,
    au.name       AS admin,
    al.action,
    al.target_type,
    al.target_id,
    al.ip_address,
    al.created_at
FROM audit_log al
LEFT JOIN admin_users au ON au.id = al.admin_id
ORDER BY al.created_at DESC
LIMIT 500;

-- ============================================================
--  PERMISSÕES
-- ============================================================
GRANT ALL ON ALL TABLES    IN SCHEMA public TO admin_user;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO admin_user;
GRANT ALL ON ALL FUNCTIONS IN SCHEMA public TO admin_user;

-- ============================================================
-- FIM DO SCRIPT
-- ============================================================
