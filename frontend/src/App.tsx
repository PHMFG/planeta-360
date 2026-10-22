import { FormEvent, useCallback, useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';

type Partnership = { parceriaId?: number; id?: number; patrocinadorId?: number; nome: string; cota?: string; edicao?: string; status?: string; situacao?: string };
type User = { nome: string; perfil: string; email?: string };
type Delivery = { id: number; titulo: string; aba?: string; status: string; prazo?: string; arquivos?: number; parceriaId?: number; patrocinadorNome?: string };
type HistoryItem = { id?: number; ocorridoEm?: string; usuario?: string; autor?: string; entidade?: string; acao?: string; valorNovo?: string };
type AuthorizedEmail = { email: string; vinculo: string; autorizadoEm?: string; acessoAtivado?: boolean };
type Tab = 'home'|'perfil'|'marca'|'midia'|'cortesias'|'cred'|'pesquisa'|'contrato'|'relatorio'|'historico';
type NavItem = { id: Tab; label: string; glyph: string; color: string };

const sponsorTabs: NavItem[] = [
  { id: 'home', label: 'Minha parceria', glyph: '⌂', color: '#073C89' },
  { id: 'perfil', label: 'Perfil', glyph: '◉', color: '#0A7D91' },
  { id: 'marca', label: 'Aplicações da marca', glyph: '✳', color: '#00862E' },
  { id: 'midia', label: 'Mídia e conteúdo', glyph: '▣', color: '#A36B00' },
  { id: 'cortesias', label: 'Cortesias', glyph: '▤', color: '#CF4000' },
  { id: 'cred', label: 'Credenciamento', glyph: '♙', color: '#D62F57' },
  { id: 'pesquisa', label: 'Pesquisa', glyph: '⌕', color: '#E1047D' },
  { id: 'contrato', label: 'Contrato', glyph: '▧', color: '#572781' },
  { id: 'relatorio', label: 'Relatório', glyph: '▤', color: '#073C89' },
  { id: 'historico', label: 'Histórico', glyph: '◷', color: '#073C89' },
];
const rbsTabs: NavItem[] = [
  { id: 'home', label: 'Gestão de patrocinadores', glyph: '▦', color: '#073C89' },
  { id: 'midia', label: 'Fila de entregas', glyph: '▣', color: '#A36B00' },
  { id: 'perfil', label: 'Empresas e acessos', glyph: '◉', color: '#0A7D91' },
  { id: 'relatorio', label: 'Relatórios', glyph: '▤', color: '#073C89' },
  { id: 'historico', label: 'Histórico', glyph: '◷', color: '#073C89' },
];
const statusOptions: [string, string][] = [
  ['NAO_INICIADO', 'Não iniciado'], ['AGUARDANDO_ENVIO', 'Aguardando envio'], ['ENVIADO', 'Recebido'],
  ['EM_ANALISE', 'Em análise'], ['AJUSTE_SOLICITADO', 'Ajuste solicitado'], ['APROVADO', 'Concluído'], ['NAO_APLICAVEL', 'Não aplicável'],
];
const statusLabels: Record<string, string> = Object.fromEntries(statusOptions);

export default function App() {
  const [email, setEmail] = useState('marketing@amarelo.example');
  const [password, setPassword] = useState('senha123');
  const [token, setToken] = useState('');
  const [user, setUser] = useState<User | null>(null);
  const [partnerships, setPartnerships] = useState<Partnership[]>([]);
  const [selected, setSelected] = useState(0);
  const [tab, setTab] = useState<Tab>('home');
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [managementDeliveries, setManagementDeliveries] = useState<Delivery[]>([]);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [authorizedEmails, setAuthorizedEmails] = useState<AuthorizedEmail[]>([]);
  const [newEmail, setNewEmail] = useState('');
  const [newEmailType, setNewEmailType] = useState('MARCA');
  const [statusDrafts, setStatusDrafts] = useState<Record<number, string>>({});
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<unknown>(null);
  const [uploadDelivery, setUploadDelivery] = useState('');
  const [uploadCategory, setUploadCategory] = useState('MATERIAL_MIDIA');
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [atlasOpen, setAtlasOpen] = useState(false);
  const [atlasQuestion, setAtlasQuestion] = useState('');
  const [atlasAnswer, setAtlasAnswer] = useState('');

  const isRbs = user?.perfil === 'ADMIN_RBS';
  const current = partnerships[selected];
  const partnershipId = Number(current?.parceriaId ?? current?.id ?? 0);
  const navItems = isRbs ? rbsTabs : sponsorTabs;
  const activeItem = navItems.find((item) => item.id === tab) || navItems[0];
  const theme = activeItem.color;

  const api = useCallback(async (path: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(path, { ...init, headers });
    if (!response.ok) {
      let message = `Erro HTTP ${response.status}`;
      try { const body = await response.json(); message = body.message || body.error || message; } catch { /* resposta sem JSON */ }
      throw new Error(message);
    }
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    return contentType.includes('application/json') ? response.json() : response;
  }, [token]);

  const notify = (message: string, isError = false) => {
    setNotice(isError ? '' : message);
    setError(isError ? message : '');
  };

  const refresh = useCallback(async () => {
    if (!partnershipId) return;
    try {
      const [d, h] = await Promise.all([
        api(`/api/parcerias/${partnershipId}/entregas`),
        api(`/api/parcerias/${partnershipId}/historico`),
      ]);
      setDeliveries(Array.isArray(d) ? d : []);
      setHistory(Array.isArray(h) ? h : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Falha ao carregar os dados.');
    }
  }, [api, partnershipId]);

  const refreshManagement = useCallback(async () => {
    if (!isRbs || !partnerships.length) return;
    try {
      const groups = await Promise.all(partnerships.map(async (p) => {
        const id = Number(p.parceriaId ?? p.id ?? 0);
        if (!id) return [] as Delivery[];
        try {
          const rows = await api(`/api/parcerias/${id}/entregas`);
          return (Array.isArray(rows) ? rows : []).map((row: Delivery) => ({ ...row, parceriaId: id, patrocinadorNome: p.nome }));
        } catch { return [] as Delivery[]; }
      }));
      setManagementDeliveries(groups.flat());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Não foi possível carregar a fila de entregas.');
    }
  }, [api, isRbs, partnerships]);

  useEffect(() => { if (token && partnershipId) void refresh(); }, [token, partnershipId, refresh]);
  useEffect(() => { if (token && isRbs && partnerships.length) void refreshManagement(); }, [token, isRbs, partnerships, refreshManagement]);
  useEffect(() => {
    if (!token || !isRbs || tab !== 'perfil' || !partnershipId) return;
    let cancelled = false;
    api(`/api/parcerias/${partnershipId}/emails-autorizados`)
      .then((rows) => { if (!cancelled) setAuthorizedEmails(Array.isArray(rows) ? rows : []); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Não foi possível consultar os acessos.'); });
    return () => { cancelled = true; };
  }, [api, isRbs, partnershipId, tab, token]);

  async function login(e: FormEvent) {
    e.preventDefault(); setBusy(true); notify('');
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, senha: password }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.message || 'Não foi possível entrar. Confira e-mail e senha.');
      const loggedUser: User = data.usuario;
      let linked: Partnership[] = data.parcerias || [];
      if (loggedUser?.perfil === 'ADMIN_RBS') {
        const allResponse = await fetch('/api/patrocinadores', { headers: { Authorization: `Bearer ${data.token}` } });
        if (allResponse.ok) linked = await allResponse.json();
      }
      if (!linked.length && loggedUser?.perfil !== 'ADMIN_RBS') throw new Error('Este e-mail ainda não possui uma parceria vinculada. Solicite a liberação à RBS.');
      setToken(data.token); setUser(loggedUser); setPartnerships(linked); setSelected(0); setTab('home');
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Falha no login.', true);
    } finally { setBusy(false); }
  }

  function logout() {
    setToken(''); setUser(null); setPartnerships([]); setDeliveries([]); setManagementDeliveries([]); setHistory([]);
    setAuthorizedEmails([]); setReport(null); setTab('home'); setAtlasOpen(false); notify('');
  }

  async function upload(e: FormEvent) {
    e.preventDefault();
    if (!uploadFile || !uploadDelivery) { notify('Selecione uma entrega e um arquivo.', true); return; }
    setBusy(true);
    try {
      const fd = new FormData(); fd.append('file', uploadFile); fd.append('categoria', uploadCategory);
      await api(`/api/entregas/${uploadDelivery}/arquivos`, { method: 'POST', body: fd });
      notify('Material enviado e registrado no histórico.'); setUploadFile(null); await refresh();
    } catch (e) { notify(e instanceof Error ? e.message : 'Falha no envio.', true); }
    finally { setBusy(false); }
  }

  async function loadReport() {
    if (!current?.patrocinadorId) { notify('Não foi possível identificar o patrocinador desta parceria.', true); return; }
    setBusy(true);
    try { setReport(await api(`/api/patrocinadores/${current.patrocinadorId}/relatorio`)); notify('Relatório consultado.'); }
    catch (e) { notify(e instanceof Error ? e.message : 'Nenhum relatório disponível.', true); }
    finally { setBusy(false); }
  }

  async function generateReport() {
    if (!current?.patrocinadorId) { notify('Selecione uma parceria válida.', true); return; }
    setBusy(true);
    try { setReport(await api(`/api/patrocinadores/${current.patrocinadorId}/relatorio`, { method: 'POST' })); notify('Relatório criado como rascunho. Revise antes de disponibilizar.'); }
    catch (e) { notify(e instanceof Error ? e.message : 'Falha ao gerar relatório.', true); }
    finally { setBusy(false); }
  }

  async function exportCsv() {
    if (!current?.patrocinadorId) return;
    try {
      const response = await fetch(`/api/patrocinadores/${current.patrocinadorId}/relatorio?formato=csv`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error('Não foi possível exportar o CSV.');
      const blob = await response.blob(); const url = URL.createObjectURL(blob); const a = document.createElement('a');
      a.href = url; a.download = 'relatorio-parceria.csv'; a.click(); URL.revokeObjectURL(url);
    } catch (e) { notify(e instanceof Error ? e.message : 'Falha na exportação.', true); }
  }

  async function saveStatus(delivery: Delivery) {
    const status = statusDrafts[delivery.id] || delivery.status;
    if (status === delivery.status) return;
    setBusy(true);
    try {
      await api(`/api/entregas/${delivery.id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) });
      notify(`Status atualizado para “${statusLabels[status] || status}”.`);
      await Promise.all([refresh(), refreshManagement()]);
    } catch (e) { notify(e instanceof Error ? e.message : 'Não foi possível atualizar o status.', true); }
    finally { setBusy(false); }
  }

  async function authorizeEmail(e: FormEvent) {
    e.preventDefault();
    if (!partnershipId || !newEmail.trim()) return;
    setBusy(true);
    try {
      await api(`/api/parcerias/${partnershipId}/emails-autorizados`, {
        method: 'POST', body: JSON.stringify({ email: newEmail.trim(), vinculo: newEmailType }),
      });
      setNewEmail('');
      const rows = await api(`/api/parcerias/${partnershipId}/emails-autorizados`);
      setAuthorizedEmails(Array.isArray(rows) ? rows : []);
      notify('E-mail autorizado para esta parceria.');
    } catch (e) { notify(e instanceof Error ? e.message : 'Não foi possível autorizar o e-mail.', true); }
    finally { setBusy(false); }
  }

  const pending = deliveries.filter((d) => !['APROVADO', 'NAO_APLICAVEL'].includes(d.status)).length;
  const sent = deliveries.filter((d) => ['ENVIADO', 'EM_ANALISE', 'APROVADO'].includes(d.status)).length;
  const visibleDeliveries = useMemo(() => deliveries.filter((d) => tab === 'marca' ? /MARCA|APLIC/i.test(d.aba || '') : tab === 'midia' && !isRbs ? /MIDIA|CONTEUDO/i.test(d.aba || '') : true), [deliveries, tab, isRbs]);

  if (!token || !user) {
    return <div className="login-page">
      <div className="login-shell">
        <section className="login-brand">
<div className="brand-mini">
  <img
    src="/assets/logoPlaneta360.png"
    alt="Planeta 360"
    className="brand-logo-image"
  />
</div>
        </section>
        <form className="login-form" onSubmit={login}>
          <div className="login-kicker">ACESSO CORPORATIVO</div><h2>Boas-vindas</h2><p className="muted">Entre com o e-mail autorizado pela RBS.</p>
          {error && <div className="notice notice-error">{error}</div>}
          <label htmlFor="email">E-mail corporativo</label><input id="email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" placeholder="voce@empresa.com.br"/>
          <label htmlFor="password">Senha</label><input id="password" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" placeholder="Digite sua senha"/>
          <button className="btn btn-primary login-submit" disabled={busy}>{busy ? 'Entrando…' : 'Entrar no portal'} <span>→</span></button>
          <div className="login-help"><span className="help-icon">i</span><p><strong>Sem acesso?</strong><br/>Confirme com a equipe RBS qual e-mail foi liberado para sua empresa.</p></div>
          <div className="login-divider"><span>AMBIENTE DE DEMONSTRAÇÃO</span></div>
          <div className="demo-logins">
            <button type="button" onClick={() => { setEmail('b2b@rbs.example'); setPassword('senha123'); }}>Entrar como RBS <span>↗</span></button>
            <button type="button" onClick={() => { setEmail('marketing@amarelo.example'); setPassword('senha123'); }}>Entrar como patrocinador <span>↗</span></button>
          </div>
          <p className="login-footnote">A área exibida é definida pelo perfil vinculado ao e-mail. Não é necessário escolher um tipo de conta ao entrar.</p>
        </form>
      </div>
    </div>;
  }

  const title = isRbs ? (tab === 'home' ? 'Gestão de patrocinadores' : activeItem.label) : (tab === 'home' ? 'Olá, ' + (current?.nome || user.nome) : activeItem.label);
  const subtitle = isRbs
    ? 'Visão operacional por empresa, entrega e acesso. Selecione uma parceria para administrar.'
    : tab === 'home' ? 'Acompanhe as próximas ações, materiais e informações da sua parceria.' : `${current?.nome || 'Sua empresa'} · ${current?.edicao ? `edição ${current.edicao}` : 'Planeta Atlântida'} · cota ${current?.cota || 'conforme contrato'}`;

  return <div className={`app-shell ${isRbs ? 'role-rbs' : 'role-sponsor'}`} style={{ '--section-color': theme } as CSSProperties}>
    <aside className="sidebar">
      <button className="sidebar-brand" onClick={() => setTab('home')} aria-label="Voltar ao início"><span className="brand-orbit">a</span><span className="sidebar-brand-name">PLANETA <b>360</b><small>{isRbs ? 'RBS · GESTÃO' : 'PORTAL DO PATROCINADOR'}</small></span></button>
      <div className="account-card">
        <div className="account-avatar">{(current?.nome || user.nome).slice(0, 1).toUpperCase()}</div>
        <div className="account-copy"><strong>{isRbs ? 'Equipe RBS' : current?.nome || user.nome}</strong><span>{isRbs ? 'Gestão de parcerias' : `${current?.cota || 'Patrocinador'} · ${current?.edicao || '2027'}`}</span></div>
      </div>
      {isRbs && <label className="partner-select-label" htmlFor="partnership-select">EMPRESA EM CONTEXTO</label>}
      {isRbs && <select id="partnership-select" className="partner-select" value={selected} onChange={(e) => { setSelected(Number(e.target.value)); setReport(null); notify(''); }} aria-label="Selecionar empresa">{partnerships.map((p, i) => <option key={`${p.parceriaId ?? p.id}-${i}`} value={i}>{p.nome} · {p.cota || 'Cota'}</option>)}</select>}
      <nav className="main-nav" aria-label="Navegação principal">
        <span className="nav-section-label">{isRbs ? 'OPERAÇÃO' : 'SUA PARCERIA'}</span>
        {navItems.map((item) => <button key={item.id} onClick={() => { setTab(item.id); notify(''); }} className={`nav-link ${tab === item.id ? 'active' : ''}`} style={{ '--item-color': item.color } as CSSProperties}><span className="nav-glyph">{item.glyph}</span><span>{item.label}</span>{tab === item.id && <span className="nav-active-dot"/>}</button>)}
      </nav>
      <div className="sidebar-bottom">
        <button className="atlas-button" onClick={() => setAtlasOpen((v) => !v)}><span className="atlas-orb">✦</span><span><b>atlas</b><small>Assistente da parceria</small></span><span className="atlas-arrow">↗</span></button>
        <button className="sidebar-help" onClick={() => { setAtlasOpen(true); setAtlasQuestion('Como consigo ajuda?'); }}><span>ⓘ</span> Ajuda</button>
        <button className="sidebar-logout" onClick={logout}><span>↪</span> Sair</button>
      </div>
    </aside>

    <main className="main-area">
      <header className="page-header" style={{ '--section-color': theme } as CSSProperties}>
        <div className="header-pattern"/>
        <div className="page-header-top"><span>{isRbs ? 'PLANETA ATLÂNTIDA / RBS · GESTÃO' : 'PLANETA ATLÂNTIDA / PATROCINADOR'}</span><span className="role-pill"><i/> {isRbs ? 'ÁREA RBS' : 'ÁREA DO PATROCINADOR'}</span></div>
        <div className="page-heading"><div><h1>{title}</h1><p>{subtitle}</p></div>{isRbs && current && <div className="header-company"><span>EMPRESA SELECIONADA</span><strong>{current.nome}</strong><small>{current.cota || 'Cota não informada'}</small></div>}</div>
      </header>
      <div className="content-area">
        {notice && <div className="notice notice-success"><span>✓</span>{notice}<button onClick={() => setNotice('')} aria-label="Fechar aviso">×</button></div>}
        {error && <div className="notice notice-error"><span>!</span>{error}<button onClick={() => setError('')} aria-label="Fechar aviso">×</button></div>}

        {tab === 'home' && !isRbs && <>
          <section className="atlas-prompt">
            <div className="atlas-prompt-top"><span className="atlas-orb">✦</span><div><strong>atlas <span>· como posso ajudar?</span></strong><small>Orientações para sua parceria, em um só lugar</small></div><button onClick={() => setAtlasOpen(true)}>Iniciar chat <span>→</span></button></div>
            <button className="atlas-input-fake" onClick={() => setAtlasOpen(true)}>Pergunte ao Atlas sobre sua parceria… <span>✧</span></button>
            <div className="quick-prompts"><button onClick={() => { setAtlasQuestion('O que falta enviar?'); setAtlasOpen(true); }}>O que falta enviar?</button><button onClick={() => { setAtlasQuestion('Como envio minha marca?'); setAtlasOpen(true); }}>Como envio minha marca?</button><button onClick={() => { setTab('relatorio'); notify(''); }}>Gerar relatório da parceria</button></div>
          </section>
          <div className="metrics-grid">
            <Metric title="A fazer" value={pending} detail="Pendências da parceria" glyph="◷"/>
            <Metric title="Enviados" value={sent} detail="Com histórico disponível" glyph="↗"/>
            <Metric title="Entregas" value={deliveries.length} detail="Acompanhadas pela RBS" glyph="▤"/>
          </div>
          <Panel title="Sua próxima ação" eyebrow="PARA VOCÊ" action={<button className="text-action" onClick={() => setTab('marca')}>Ver aplicações da marca →</button>}>
            <div className="next-action"><div className="next-action-icon">✳</div><div className="next-action-copy"><strong>Confira os materiais da sua parceria</strong><p>Envie o logo, o manual de identidade visual e os materiais solicitados pela RBS. Cada envio fica registrado com data, responsável e versão.</p><div className="action-row"><Action onClick={() => setTab('marca')}>Enviar logo e MIV</Action><Action secondary onClick={() => setTab('midia')}>Enviar material de mídia</Action></div></div><span className="next-arrow">↗</span></div>
          </Panel>
          <Panel title="Entregas e prazos" eyebrow="ACOMPANHAMENTO" action={<button className="text-action" onClick={() => setTab('historico')}>Ver histórico completo →</button>}><DeliveryTable rows={deliveries.slice(0, 5)}/></Panel>
          <div className="info-strip"><span>ⓘ</span><p><strong>Informações da parceria</strong> — sua cota, os benefícios e as permissões são configurados pela RBS conforme o contrato.</p></div>
        </>}

        {tab === 'home' && isRbs && <>
          <div className="metrics-grid metrics-rbs"><Metric title="Entregas registradas" value={managementDeliveries.length} detail="Em todas as parcerias" glyph="▤"/><Metric title="Pendências" value={managementDeliveries.filter((d) => !['APROVADO','NAO_APLICAVEL'].includes(d.status)).length} detail="Acompanhar por empresa" glyph="◷"/><Metric title="Concluídas" value={managementDeliveries.filter((d) => ['APROVADO','NAO_APLICAVEL'].includes(d.status)).length} detail="Status final registrado" glyph="✓"/></div>
          <Panel title="Fila de entregas" eyebrow="RESUMO OPERACIONAL" action={<button className="text-action" onClick={() => setTab('midia')}>Abrir fila completa →</button>}>
            <p className="panel-intro">Acompanhe materiais enviados, pendências e status por patrocinador. Selecione uma empresa para consultar o contexto completo.</p>
            <ManagementTable rows={managementDeliveries.slice(0, 6)} statusDrafts={statusDrafts} setStatusDrafts={setStatusDrafts} onSave={saveStatus} busy={busy} onSelect={(row) => { const idx = partnerships.findIndex((p) => Number(p.parceriaId ?? p.id) === Number(row.parceriaId)); if (idx >= 0) setSelected(idx); setTab('midia'); }}/>
          </Panel>
          <div className="rbs-shortcuts"><button onClick={() => setTab('perfil')}><span className="shortcut-icon teal">◉</span><span><b>Empresas e acessos</b><small>Gerencie os e-mails autorizados</small></span><span>→</span></button><button onClick={() => setTab('relatorio')}><span className="shortcut-icon blue">▤</span><span><b>Relatórios</b><small>Consulte e gere relatórios</small></span><span>→</span></button></div>
        </>}

        {tab === 'perfil' && !isRbs && <>
          <div className="detail-grid"><Field label="Empresa / patrocinador" value={current?.nome}/><Field label="Cota contratada" value={current?.cota}/><Field label="Edição" value={current?.edicao}/><Field label="Situação da parceria" value={current?.situacao || current?.status || 'Conforme cadastro da RBS'}/><Field label="E-mail de acesso" value={user.email}/><Field label="Perfil de acesso" value="Patrocinador"/></div>
          <Panel title="Plano comercial" eyebrow="DOCUMENTOS DA PARCERIA"><div className="document-row"><div className="document-icon">PDF</div><div className="document-copy"><strong>Plano comercial da parceria</strong><p>Publicado pela RBS · versão e data conforme documento vigente</p><span className="status-label status-neutral">Somente consulta</span></div><button className="btn btn-outline" onClick={() => notify('O plano comercial será disponibilizado pela RBS quando estiver anexado à parceria.', true)}>Consultar plano</button></div></Panel>
          <div className="info-strip"><span>ⓘ</span><p><strong>Cadastro divergente?</strong> Solicite uma correção à RBS. O patrocinador não altera a própria cota nem libera novos acessos.</p></div>
        </>}

        {tab === 'perfil' && isRbs && <>
          <Panel title="Empresa selecionada" eyebrow="CONFIGURAÇÃO ADMINISTRATIVA"><div className="detail-grid compact"><Field label="Patrocinador" value={current?.nome}/><Field label="Cota contratada" value={current?.cota}/><Field label="Edição" value={current?.edicao}/><Field label="Situação" value={current?.situacao || current?.status || '—'}/></div></Panel>
          <Panel title="Permissões individuais" eyebrow="E-MAILS AUTORIZADOS">
            <form className="authorize-form" onSubmit={authorizeEmail}><div className="form-field"><label htmlFor="new-email">E-mail corporativo</label><input id="new-email" type="email" required placeholder="pessoa@empresa.com.br" value={newEmail} onChange={(e) => setNewEmail(e.target.value)}/></div><div className="form-field"><label htmlFor="email-type">Vínculo</label><select id="email-type" value={newEmailType} onChange={(e) => setNewEmailType(e.target.value)}><option value="MARCA">Patrocinador / marca</option><option value="AGENCIA">Agência</option></select></div><button className="btn btn-primary" disabled={busy || !partnershipId}>Autorizar e-mail <span>+</span></button></form>
            <div className="table-wrap"><table className="data-table"><thead><tr><th>E-mail</th><th>Vínculo</th><th>Acesso</th><th>Autorizado em</th></tr></thead><tbody>{authorizedEmails.map((item) => <tr key={item.email}><td className="table-primary">{item.email}</td><td>{item.vinculo === 'AGENCIA' ? 'Agência' : 'Patrocinador'}</td><td><span className={`status-label ${item.acessoAtivado ? 'status-ok' : 'status-warn'}`}>{item.acessoAtivado ? 'Ativado' : 'Aguardando primeiro acesso'}</span></td><td>{formatDate(item.autorizadoEm)}</td></tr>)}</tbody></table>{!authorizedEmails.length && <Empty text="Nenhum e-mail autorizado encontrado para esta parceria."/>}</div>
          </Panel>
          <div className="info-strip info-teal"><span>ⓘ</span><p>Arquivos, resultados e relatórios pertencem à empresa selecionada. Confira o contexto antes de publicar informações ou alterar acessos.</p></div>
        </>}

        {(tab === 'marca' || (tab === 'midia' && !isRbs)) && <>
          <Panel title={tab === 'marca' ? 'Enviar materiais da marca' : 'Enviar material de mídia'} eyebrow={tab === 'marca' ? 'LOGO E IDENTIDADE VISUAL' : 'CAMPANHAS E CONTEÚDO'}>
            <p className="panel-intro">{tab === 'marca' ? 'Envie o logo e o manual de identidade visual (MIV) para orientar as aplicações da marca no evento.' : 'Envie os materiais vinculados às entregas da sua parceria. Os arquivos ficam registrados no histórico.'}</p>
            <form onSubmit={upload} className="upload-form">
              <label className="upload-drop" htmlFor="upload-file"><span className="upload-icon">↑</span><strong>{uploadFile ? uploadFile.name : 'Arraste seus documentos aqui'}</strong><span>{uploadFile ? `${(uploadFile.size / 1024 / 1024).toFixed(2)} MB · arquivo selecionado` : 'ou selecione no computador · limite de 20 MB'}</span><span className="btn btn-outline">Selecionar arquivo</span><input id="upload-file" required type="file" onChange={(e) => setUploadFile(e.target.files?.[0] || null)}/></label>
              <div className="upload-fields"><div className="form-field"><label htmlFor="delivery">Entrega vinculada</label><select id="delivery" required value={uploadDelivery} onChange={(e) => setUploadDelivery(e.target.value)}><option value="">Selecione uma entrega</option>{visibleDeliveries.map((d) => <option key={d.id} value={d.id}>{d.titulo} · {statusLabels[d.status] || d.status}</option>)}</select></div><div className="form-field"><label htmlFor="category">Tipo de material</label><select id="category" value={uploadCategory} onChange={(e) => setUploadCategory(e.target.value)}><option value="MATERIAL_MARCA">Logo / identidade visual</option><option value="MATERIAL_MIDIA">Mídia e conteúdo</option><option value="COMPROVACAO">Comprovação / evidência</option><option value="OUTRO">Outro documento</option></select></div></div>
              <div className="upload-actions"><span className="muted small">O envio gera um registro de versão, autor e data/hora.</span><button className="btn btn-primary" disabled={busy}>{busy ? 'Enviando…' : 'Enviar material'} <span>→</span></button></div>
            </form>
          </Panel>
          <Panel title="Entregas relacionadas" eyebrow="ARQUIVOS E STATUS"><DeliveryTable rows={visibleDeliveries}/></Panel>
        </>}

        {tab === 'midia' && isRbs && <Panel title="Fila de entregas" eyebrow="GESTÃO OPERACIONAL" action={<button className="text-action" onClick={() => void refreshManagement()}>Atualizar fila ↻</button>}>
          <div className="filter-bar"><div className="filter-context"><span className="filter-dot"/><span>Contexto atual</span><strong>{current?.nome || 'Selecione uma empresa'}</strong></div><p>Atualize o status de cada entrega. A mudança fica registrada no histórico da parceria.</p></div>
          <ManagementTable rows={managementDeliveries} statusDrafts={statusDrafts} setStatusDrafts={setStatusDrafts} onSave={saveStatus} busy={busy} onSelect={(row) => { const idx = partnerships.findIndex((p) => Number(p.parceriaId ?? p.id) === Number(row.parceriaId)); if (idx >= 0) setSelected(idx); }}/>
        </Panel>}

        {tab === 'cortesias' && <>
          <Panel title="Destinação de ingressos" eyebrow="CONFORME COTA E CONTRATO"><p className="panel-intro">Consulte as orientações de cortesias liberadas pela RBS. As quantidades válidas dependem do contrato e da configuração da sua parceria.</p><div className="benefit-grid"><div className="benefit-card"><span className="benefit-symbol">01</span><div><strong>Arena / Camarote</strong><p>Passaporte, sexta e sábado</p></div><span className="status-label status-neutral">Conforme contrato</span></div><div className="benefit-card"><span className="benefit-symbol">02</span><div><strong>Premium / Lounge</strong><p>Disponibilidade definida pela RBS</p></div><span className="status-label status-neutral">Conforme contrato</span></div></div></Panel>
          <div className="info-strip info-orange"><span>ⓘ</span><p><strong>Liberação e destinação</strong> — a RBS libera as quantidades contratadas. A destinação deve respeitar o saldo disponível para cada setor, tipo e dia.</p></div>
          <Panel title="Orientações de distribuição" eyebrow="DOCUMENTO DA RBS"><p className="panel-intro">As regras e os formulários vigentes serão disponibilizados aqui quando publicados pela equipe RBS.</p><button className="btn btn-outline" onClick={() => notify('As orientações ainda não foram anexadas a esta parceria.', true)}>Consultar orientações</button></Panel>
        </>}

        {tab === 'cred' && <Panel title="Sua liberação de credenciamento" eyebrow="ACESSO AO EVENTO"><div className="credential-summary"><div className="credential-icon">♙</div><div><strong>Setor e quantidade conforme contrato</strong><p>O período de cadastro e os limites são definidos pela RBS para cada parceria.</p></div></div><button className="btn btn-primary" onClick={() => notify('O endereço do Open Staff precisa ser configurado pela RBS.', true)}>Abrir Open Staff <span>↗</span></button><div className="info-strip info-pink mt-panel"><span>ⓘ</span><p>O cadastro de pessoas acontece em um serviço externo. Este portal apresenta orientações e não confirma cadastros ou sincronização com o Open Staff.</p></div></Panel>}

        {tab === 'pesquisa' && <>
          <Panel title="Perguntas da sua marca" eyebrow="DISPONÍVEL PARA COTA MASTER"><p className="panel-intro">Cada marca autorizada tem direito a até três perguntas personalizadas para a pesquisa oficial do evento. O prazo é informado pela RBS.</p><div className="research-steps"><div><span>01</span><strong>Antes do evento</strong><p>Sua marca envia as perguntas.</p></div><div><span>02</span><strong>Durante o evento</strong><p>A pesquisa oficial é aplicada ao público.</p></div><div><span>03</span><strong>Depois do evento</strong><p>Os resultados são publicados quando liberados.</p></div></div><div className="info-strip info-pink"><span>ⓘ</span><p>A interface de envio de perguntas e a importação de resultados ainda dependem de integração. Nenhum dado é salvo por esta tela.</p></div></Panel>
          <Panel title="Resultados da pesquisa" eyebrow="PUBLICAÇÃO RBS"><Empty text="Resultados ainda não disponibilizados para esta parceria."/></Panel>
        </>}

        {tab === 'contrato' && <>
          <div className="contract-layout"><Panel title="Contrato final assinado" eyebrow="DOCUMENTO OFICIAL"><div className="contract-preview"><div className="contract-preview-head"><span>PDF</span><span>Somente consulta</span></div><div className="contract-paper"><div className="contract-paper-logo">PLANETA 360</div><h3>Contrato de patrocínio</h3><p>Planeta Atlântida · {current?.nome || 'Patrocinador'}</p>{Array.from({ length: 7 }).map((_, i) => <span key={i}/>)}</div><p className="muted small">Pré-visualização ilustrativa. O documento oficial precisa ser publicado pela RBS.</p></div></Panel><div className="contract-side"><Panel title="Signatários"><Field label="Patrocinador" value="Conforme contrato assinado"/><Field label="RBS" value="Conforme contrato assinado"/><Field label="Data de assinatura" value="Conforme documento"/></Panel><button className="btn btn-primary btn-full" onClick={() => notify('O contrato assinado ainda não foi anexado a esta parceria.', true)}>Baixar contrato <span>↓</span></button></div></div>
          <div className="info-strip info-purple"><span>ⓘ</span><p>Esta tela é apenas para consulta do contrato final disponibilizado pela RBS. Ela não realiza assinatura eletrônica nem altera o documento.</p></div>
        </>}

        {tab === 'relatorio' && <>
          <Panel title={isRbs ? 'Relatórios das parcerias' : 'Relatório da parceria'} eyebrow="CONSOLIDAÇÃO E INDICADORES"><p className="panel-intro">O relatório usa dados disponíveis e autorizados no banco. Informações ausentes devem continuar sinalizadas como indisponíveis; a publicação exige validação humana da RBS.</p><div className="report-scope"><div><span>ESCOPO</span><strong>{isRbs ? current?.nome || 'Empresa selecionada' : current?.nome || 'Sua parceria'}</strong></div><div><span>PERÍODO</span><strong>Ciclo disponível no sistema</strong></div></div><div className="report-checks"><span>✓ Dados da parceria</span><span>✓ Materiais e versões</span><span>✓ Entregas e pendências</span><span>✓ Histórico autorizado</span><span>✓ Indicadores disponíveis</span><span>! Itens sem dados serão sinalizados</span></div><div className="action-row"><Action onClick={isRbs ? generateReport : loadReport}>{isRbs ? 'Gerar rascunho' : 'Consultar relatório'}</Action><Action secondary onClick={exportCsv}>Exportar CSV</Action>{isRbs && <Action secondary onClick={loadReport}>Consultar relatório existente</Action>}</div>
            {report !== null && <div className="report-result"><div className="report-result-head"><strong>Retorno da API</strong><span className="status-label status-warn">{isRbs ? 'Rascunho para revisão' : 'Consulta'}</span></div><pre>{JSON.stringify(report, null, 2)}</pre></div>}
          </Panel>
        </>}

        {tab === 'historico' && <Panel title="Histórico da parceria" eyebrow="REGISTROS E RASTREABILIDADE"><p className="panel-intro">Os eventos mostram alterações e envios registrados na parceria selecionada.</p>{history.length ? <div className="timeline">{history.map((item, i) => <div className="timeline-item" key={item.id ?? i}><span className="timeline-dot"/><div className="timeline-date">{formatDate(item.ocorridoEm)}</div><div className="timeline-copy"><strong>{humanize(item.acao || item.entidade || 'Atualização')}</strong><p>{item.autor || item.usuario || 'Usuário'}{item.valorNovo ? ` · ${item.valorNovo}` : ''}</p></div></div>)}</div> : <Empty text="Nenhum evento de histórico disponível para esta parceria."/>}</Panel>}

        <footer className="page-footer"><span>PLANETA 360</span><span>Portal de parcerias · Acesso protegido pela API</span><span>© Planeta Atlântida</span></footer>
      </div>
    </main>

    {atlasOpen && <div className="atlas-overlay" role="dialog" aria-modal="true" aria-label="Assistente Atlas"><div className="atlas-chat"><header><div className="atlas-chat-icon">✦</div><div><strong>atlas</strong><small>Assistente da parceria</small></div><button onClick={() => setAtlasOpen(false)} aria-label="Fechar Atlas">×</button></header><div className="atlas-chat-body"><div className="atlas-welcome"><span className="atlas-orb">✦</span><h3>Olá! Como posso ajudar?</h3><p>Encontre orientações e entenda as próximas ações da parceria.</p></div>{atlasAnswer && <div className="atlas-answer"><strong>atlas · orientação</strong><p>{atlasAnswer}</p></div>}<div className="atlas-suggestions"><button onClick={() => { setAtlasQuestion('O que falta enviar?'); setAtlasAnswer(`Há ${pending} entrega(s) pendente(s) na parceria selecionada. Consulte “Minha parceria” para ver a lista e “Aplicações da marca” ou “Mídia e conteúdo” para enviar arquivos.`); }}>O que falta enviar?</button><button onClick={() => { setAtlasQuestion('Como envio minha marca?'); setAtlasAnswer('Acesse “Aplicações da marca”, selecione a entrega relacionada e envie o logo ou o manual de identidade visual. O envio fica registrado com versão, responsável e data/hora.'); }}>Como envio minha marca?</button><button onClick={() => { setAtlasQuestion('Onde vejo o contrato?'); setAtlasAnswer('Abra a aba “Contrato”. O documento final só poderá ser baixado quando a RBS o publicar na sua parceria.'); }}>Onde vejo o contrato?</button></div><p className="atlas-disclaimer">Respostas de orientação demonstrativas. Regras e dados oficiais dependem das fontes autorizadas da parceria.</p></div><form className="atlas-chat-input" onSubmit={(e) => { e.preventDefault(); const q = atlasQuestion.toLowerCase(); setAtlasAnswer(q.includes('marca') ? 'Acesse “Aplicações da marca” e selecione o arquivo e a entrega vinculada. Consulte a orientação vigente da RBS para formatos e limites.' : q.includes('contrato') ? 'Acesse “Contrato”. O arquivo assinado será exibido quando a RBS publicar o documento oficial.' : 'Consulte “Minha parceria” para identificar entregas pendentes e abra a seção correspondente para realizar a próxima ação.'); }}><input value={atlasQuestion} onChange={(e) => setAtlasQuestion(e.target.value)} placeholder="Pergunte ao Atlas sobre sua parceria…"/><button aria-label="Enviar pergunta">→</button></form></div></div>}
  </div>;
}

function Metric({ title, value, detail, glyph }: { title: string; value: number; detail: string; glyph: string }) {
  return <div className="metric-card"><div className="metric-top"><span>{title}</span><span className="metric-glyph">{glyph}</span></div><div className="metric-value">{String(value).padStart(2, '0')}</div><p>{detail}</p></div>;
}
function Panel({ title, eyebrow, action, children }: { title: string; eyebrow?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="panel"><div className="panel-heading"><div>{eyebrow && <span className="panel-eyebrow">{eyebrow}</span>}<h2>{title}</h2></div>{action}</div>{children}</section>;
}
function Action({ children, onClick, secondary = false }: { children: ReactNode; onClick: () => void; secondary?: boolean }) {
  return <button type="button" onClick={onClick} className={`btn ${secondary ? 'btn-outline' : 'btn-primary'}`}>{children}</button>;
}
function Field({ label, value }: { label: string; value?: string }) {
  return <div className="detail-field"><span>{label}</span><strong>{value || 'Não informado'}</strong></div>;
}
function Empty({ text }: { text: string }) { return <div className="empty-state"><span>▤</span><p>{text}</p></div>; }
function DeliveryTable({ rows }: { rows: Delivery[] }) {
  if (!rows.length) return <Empty text="Nenhuma entrega encontrada para esta parceria."/>;
  return <div className="table-wrap"><table className="data-table"><thead><tr><th>Entrega</th><th>Seção</th><th>Status</th><th>Prazo</th></tr></thead><tbody>{rows.map((row) => <tr key={row.id}><td><div className="table-title"><span className="file-mini">PDF</span><span className="table-primary">{row.titulo}</span></div></td><td>{humanize(row.aba || '—')}</td><td><StatusBadge status={row.status}/></td><td>{formatDate(row.prazo)}</td></tr>)}</tbody></table></div>;
}
function ManagementTable({ rows, statusDrafts, setStatusDrafts, onSave, busy, onSelect }: { rows: Delivery[]; statusDrafts: Record<number, string>; setStatusDrafts: (fn: (old: Record<number, string>) => Record<number, string>) => void; onSave: (row: Delivery) => void; busy: boolean; onSelect: (row: Delivery) => void }) {
  if (!rows.length) return <Empty text="Ainda não há entregas para exibir. Confira a empresa selecionada ou atualize a fila."/>;
  return <div className="table-wrap"><table className="data-table management-table"><thead><tr><th>Patrocinador / entrega</th><th>Prazo</th><th>Status atual</th><th>Atualizar status</th><th/></tr></thead><tbody>{rows.map((row) => <tr key={`${row.parceriaId ?? 'p'}-${row.id}`}><td><button className="management-title" onClick={() => onSelect(row)}><strong>{row.patrocinadorNome || 'Parceria selecionada'}</strong><span>{row.titulo}</span><small>{humanize(row.aba || 'Entrega')} · {row.arquivos ?? 0} arquivo(s)</small></button></td><td>{formatDate(row.prazo)}</td><td><StatusBadge status={row.status}/></td><td><select aria-label={`Novo status para ${row.titulo}`} value={statusDrafts[row.id] ?? row.status} onChange={(e) => setStatusDrafts((old) => ({ ...old, [row.id]: e.target.value }))}>{statusOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></td><td><button className="btn btn-small btn-outline" disabled={busy || (statusDrafts[row.id] ?? row.status) === row.status} onClick={() => onSave(row)}>Salvar</button></td></tr>)}</tbody></table></div>;
}
function StatusBadge({ status }: { status: string }) {
  const cls = status === 'APROVADO' ? 'status-ok' : status === 'AJUSTE_SOLICITADO' ? 'status-error' : status === 'NAO_APLICAVEL' ? 'status-neutral' : ['ENVIADO', 'EM_ANALISE'].includes(status) ? 'status-warn' : 'status-muted';
  return <span className={`status-label ${cls}`}><i/>{statusLabels[status] || humanize(status)}</span>;
}
function humanize(value: string) { return value.replace(/_/g, ' ').toLowerCase().replace(/(^|\s)\S/g, (letter) => letter.toUpperCase()); }
function formatDate(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' }).format(date);
}
