import React, {useEffect, useMemo, useRef, useState} from 'react';
import {hasExactSimulation, hasPermission, isAdministrator} from './auth.js';
import {request, downloadText} from './api.js';
import {displayState, formatDate, isActiveRun, localDate, selectedInPlanOrder, toggleVisible, planMatchesContext} from './helpers.js';

function BranchIcon() {
  return <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M6 7v10M6 13c8 0 12-1 12-6" stroke="currentColor" strokeWidth="1.8"/>
    <circle cx="6" cy="5" r="2.5" stroke="currentColor" strokeWidth="1.8"/>
    <circle cx="6" cy="19" r="2.5" stroke="currentColor" strokeWidth="1.8"/>
    <circle cx="18" cy="5" r="2.5" stroke="currentColor" strokeWidth="1.8"/>
  </svg>;
}

function Confirmation({value, onCancel, onConfirm, busy}) {
  const dialog = useRef(null);
  useEffect(() => {
    if (value && !dialog.current.open) dialog.current.showModal();
    if (!value && dialog.current.open) dialog.current.close();
  }, [value]);
  return <dialog ref={dialog} onCancel={event => {event.preventDefault(); if (!busy) onCancel();}}>
    {value && <>
      <div className="eyebrow">CONFIRMAR OPERAÇÃO</div>
      <h2>{value.kind === 'switch' ? 'Trocar a branch ativa?' : 'Aplicar os commits selecionados?'}</h2>
      <p>{value.kind === 'switch' ? 'O checkout será alterado para a branch abaixo.' :
        `Serão aplicados ${value.count} commits na branch abaixo, na ordem apresentada.`}</p>
      <div className="confirm-branch"><BranchIcon/><strong>{value.target}</strong></div>
      {value.repositoryPath && <p className="muted">{value.repositoryPath}</p>}
      {value.kind !== 'switch' && <p className="muted">Se houver conflito, a sequência para para você resolver no terminal ou na IDE.</p>}
      <div className="actions end"><button className="secondary" onClick={onCancel} disabled={busy}>Cancelar</button>
        <button className="primary" onClick={onConfirm} disabled={busy}>{busy ? 'Aguarde…' : value.kind === 'switch' ? 'Trocar branch' : 'Aplicar commits'}</button></div>
    </>}
  </dialog>;
}

export default function App({user, environments, onLockChange, onCreateEnvironment}) {
  const [environmentId, setEnvironmentId] = useState('');
  const environment = environments.find(item => item.id === environmentId && item.enabled);
  const allowed = permission => hasPermission(user, environment, permission);
  const [path, setPath] = useState('');
  const [repo, setRepo] = useState(null);
  const [source, setSource] = useState('');
  const [target, setTarget] = useState('');
  const [cutoff, setCutoff] = useState(localDate());
  const [plan, setPlan] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [search, setSearch] = useState('');
  const [run, setRun] = useState(null);
  const [history, setHistory] = useState([]);
  const [busy, setBusy] = useState(false);
  const [settling, setSettling] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmation, setConfirmation] = useState(null);
  const [followLog, setFollowLog] = useState(true);
  const fileInput = useRef(null);
  const logPanel = useRef(null);
  const running = isActiveRun(run);
  const locked = busy || running || settling;
  useEffect(() => { onLockChange?.(locked); }, [locked, onLockChange]);

  function invalidate() { setPlan(null); setSelected(new Set()); setSearch(''); setNotice(''); }

  function acceptRepository(view, keepSource = false) {
    setRepo(view);
    setPath(view.path);
    setTarget(view.currentBranch || view.branches.find(branch => !branch.remote)?.name || '');
    setSource(old => keepSource && view.branches.some(branch => branch.ref === old) ? old :
      view.branches.find(branch => branch.name !== view.currentBranch)?.ref ||
      view.branches.find(branch => branch.name === view.currentBranch)?.ref || '');
  }

  async function act(action) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  useEffect(() => {
    if (locked) return;
    if (!environment) {setRepo(null);setPath('');setSource('');setTarget('');invalidate();setRun(null);setHistory([]);setConfirmation(null);return;}
    let cancelled = false;
    setRepo(null); setPath(environment.repositoryPath); setSource(''); setTarget('');
    invalidate(); setRun(null); setHistory([]); setConfirmation(null); setError(''); setBusy(true);
    Promise.all([request('/repositories/open', {method:'POST',body:{environmentId:environment.id}}),request('/runs')])
      .then(([view,runs])=>{if(!cancelled){acceptRepository(view);setHistory(runs.filter(item=>item.environmentId===environment.id));}})
      .catch(err=>{if(!cancelled)setError(err.message);}).finally(()=>{if(!cancelled)setBusy(false);});
    return ()=>{cancelled=true;};
  }, [environment?.id, environment?.revision]);

  useEffect(() => {
    if (!run || !isActiveRun(run)) return;
    let stopped = false;
    let timer;
    const id = run.id;
    async function poll() {
      let terminal = false;
      try {
        const current = await request('/runs/' + id);
        if (stopped) return;
        if (!isActiveRun(current)) setSettling(true);
        setRun(current);
        if (isActiveRun(current)) timer = setTimeout(poll, 900);
        else {
          terminal = true;
          setHistory((await request('/runs')).filter(item => item.environmentId === environment.id));
          const view = await request('/repositories/open', {method: 'POST', body: {environmentId: environment.id}});
          if (!stopped) {
            setRepo(view);
            setPath(view.path);
            if (!current.simulation) {
              setTarget(view.currentBranch);
              invalidate();
            }
            setNotice(current.state === 'COMPLETED' ? (current.simulation ? 'Simulação concluída. Confira o resultado e o log.' : 'Execução concluída. Gere uma nova prévia para conferir o que falta.') : '');
          }
        }
      } catch (err) { if (!stopped) { setError(err.message); if (!terminal) timer = setTimeout(poll, 2000); } }
      finally { if (terminal && !stopped) setSettling(false); }
    }
    poll();
    return () => { stopped = true; clearTimeout(timer); };
  }, [run?.id]);

  useEffect(() => {
    if (followLog && logPanel.current) logPanel.current.scrollTop = logPanel.current.scrollHeight;
  }, [run?.lines?.length, followLog]);

  const visible = useMemo(() => {
    const needle = search.toLocaleLowerCase('pt-BR');
    return (plan?.commits || []).filter(commit => (commit.message + ' ' + commit.hash + ' ' + commit.date).toLocaleLowerCase('pt-BR').includes(needle));
  }, [plan, search]);
  const hashes = selectedInPlanOrder(plan?.commits || [], selected);
  const allVisible = visible.length > 0 && visible.every(commit => selected.has(commit.hash));
  const progress = new Map((run?.commits || []).map(commit => [commit.hash, commit]));
  const targetActive = Boolean(repo && target && target === repo.currentBranch);
  const matchesContext = Boolean(environment && planMatchesContext(plan, repo, source, target, cutoff, environment));
  const inputChanged = Boolean(repo && path.trim() !== repo.path);
  const simulationReady = isAdministrator(user) || hasExactSimulation(history, user, plan?.id, hashes, environment);
  const canApply = Boolean(allowed('APPLY') && simulationReady && matchesContext && !inputChanged && hashes.length && targetActive && !repo?.dirtyTracked && !repo?.operation && !locked);
  const applyBlocked = locked ? 'Aguarde a operação atual finalizar.' :
    !matchesContext || inputChanged ? 'Gere uma prévia para o repositório, branches e data atuais.' :
    !hashes.length ? 'Selecione ao menos um commit.' :
    !targetActive ? (allowed('SWITCH') ? 'Ative a branch de destino antes de aplicar.' : 'Solicite a um administrador que ative a branch de destino.') :
    repo?.dirtyTracked ? 'Guarde as alterações locais em commit ou stash antes de aplicar.' :
    repo?.operation ? 'Finalize a operação Git em andamento antes de aplicar.' :
    !simulationReady ? 'Conclua uma simulação sua com esta mesma prévia e seleção de hashes, na mesma ordem.' : '';

  async function openRepository(event) {
    event?.preventDefault();
    if (!environment) return;
    await act(async () => {
      const view = await request('/repositories/open', {method: 'POST', body: {environmentId: environment.id}});
      acceptRepository(view);
      invalidate();
    });
  }

  async function prepare() {
    await act(async () => {
      const preview = await request('/plans', {method: 'POST', body: {environmentId: environment.id, sourceBranch: source, targetBranch: target, cutoff}});
      setPlan(preview); setSelected(new Set(preview.commits.map(commit => commit.hash))); setSearch('');
      if (preview.commits.length === 0) setNotice('Não há commits pendentes até essa data para as branches selecionadas.');
    });
  }

  async function exportFile() {
    await act(async () => {
      const content = await request('/plans/' + plan.id + '/export', {method: 'POST', body: {selectedHashes: hashes, simulation: true}});
      downloadText('commits-cherry-pick.txt', content);
    });
  }

  async function importFile(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !repo) return;
    await act(async () => {
      if (file.size > 2_000_000) throw new Error('Selecione um TXT com até 2 MB.');
      const preview = await request('/plans/import', {method: 'POST', body: {environmentId: environment.id, targetBranch: target, content: await file.text()}});
      setPlan(preview); setSource(preview.sourceBranch); setTarget(preview.targetBranch); setCutoff(preview.cutoff);
      setSelected(new Set(preview.commits.map(commit => commit.hash))); setSearch('');
      setNotice(`TXT validado: ${preview.commits.length} commits ainda pendentes.`);
    });
  }

  async function start(simulation) {
    if (!simulation && !simulationReady) { setError('Conclua uma simulação sua para esta prévia e seleção exata, na mesma ordem.'); return; }
    if (!matchesContext || inputChanged) { setError('A prévia não corresponde ao repositório ou às branches atuais. Gere a lista novamente.'); return; }
    await act(async () => {
      const created = await request('/plans/' + plan.id + '/runs', {method: 'POST', body: {selectedHashes: hashes, simulation}});
      setRun(created); setFollowLog(true); setConfirmation(null);
      setHistory((await request('/runs')).filter(item => item.environmentId === environment.id));
    });
  }

  async function confirm() {
    if (confirmation.kind === 'apply') { await start(false); return; }
    await act(async () => {
      const view = await request('/repositories/switch', {method: 'POST', body: {environmentId: environment.id, targetBranch: target}});
      acceptRepository(view, true); invalidate(); setConfirmation(null);
    });
  }

  return <div className="app-shell">
    <main>
      <div className="page-intro"><div><div className="eyebrow">BRANCHES & CHERRY-PICKS</div>
        <h1>Traga os commits certos.</h1><p>Compare branches, revise as alterações e acompanhe cada aplicação.</p></div>
        <div className="step-list"><span>1. Repositório</span><b>→</b><span>2. Seleção</span><b>→</b><span>3. Execução</span></div></div>

      {error && <div className="alert error" role="alert"><strong>Não foi possível concluir</strong><span>{error}</span><button onClick={() => setError('')} aria-label="Fechar erro">×</button></div>}
      {notice && <div className="alert success" role="status">{notice}</div>}

      <section className="panel repository-panel" aria-labelledby="repository-title">
        <div className="section-heading"><div><span className="section-number">01</span><h2 id="repository-title">Repositório</h2></div>
        </div>
        <form className="repository-form" onSubmit={openRepository}>
          <label className="grow">Ambiente<select value={environmentId} disabled={locked} onChange={event=>setEnvironmentId(event.target.value)}><option value="">Selecione um ambiente</option>{environments.filter(item=>item.enabled && ['PREPARE','SIMULATE','APPLY','FETCH','SWITCH'].some(permission=>hasPermission(user,item,permission))).map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="grow">Repositório<input readOnly value={environment?.repositoryPath || ''}/></label>
          <button className="primary" disabled={locked || !environment}>Atualizar estado</button>
          {allowed('FETCH') && <button className="secondary" type="button" disabled={!repo || locked} onClick={() => act(async () => {
            const view = await request('/repositories/fetch', {method: 'POST', body: {environmentId: environment.id}});
            acceptRepository(view, true); invalidate(); setNotice('Referências remotas atualizadas.');
          })}>Atualizar refs (fetch)</button>}
        </form>
        {!environments.some(item=>item.enabled) && <div className="empty-state"><strong>Nenhum ambiente disponível</strong><p>{isAdministrator(user)?'Crie um ambiente para começar.':'Solicite acesso a um ambiente ao administrador.'}</p>{isAdministrator(user)&&<button className="primary" onClick={onCreateEnvironment}>Criar ambiente</button>}</div>}
        {repo && <div className="repository-state">
          <span className="branch-pill"><BranchIcon/>{repo.currentBranch || 'Branch atual fora do acesso deste ambiente'}</span>
          <code title={repo.head}>HEAD {repo.head.slice(0, 9)}</code>
          <span className={'status-pill ' + (repo.dirtyTracked || repo.operation ? 'amber' : 'green')}>{repo.operation ? 'Operação Git em andamento' : repo.dirtyTracked ? 'Alterações locais' : 'Pronto para aplicar'}</span>
          <button className="link-button" disabled={locked} onClick={openRepository}>Atualizar estado</button>
        </div>}
        {repo?.dirtyTracked && <details className="dirty-details"><summary>Arquivos versionados alterados — guarde em commit ou stash antes de aplicar</summary><pre>{repo.changedFiles.join('\n')}</pre></details>}
        {repo?.operation && <p className="inline-warning">Operação ativa: <code>{repo.operation}</code>. Confira <code>git status</code> e finalize ou aborte antes de aplicar outros commits.</p>}
      </section>

      <div className="content-grid"><div className="main-column">
        <section className="panel" aria-labelledby="compare-title">
          <div className="section-heading"><div><span className="section-number">02</span><h2 id="compare-title">Comparar e selecionar</h2></div></div>
          <div className="comparison-fields">
            <label>Branch de origem<select value={source} disabled={!repo || locked} onChange={event => {setSource(event.target.value); invalidate();}}>
              <option value="">Selecione a origem</option>{repo?.branches.map(branch => <option key={branch.ref} value={branch.ref}>{branch.name}{branch.remote ? ' (remota)' : ''}</option>)}</select></label>
            <span className="flow-arrow" aria-hidden="true">→</span>
            <label>Branch de destino<select value={target} disabled={!repo || locked} onChange={event => {setTarget(event.target.value); invalidate();}}>
              <option value="">Selecione o destino</option>{repo?.branches.filter(branch => !branch.remote).map(branch => <option key={branch.ref} value={branch.name}>{branch.name}{branch.name === repo.currentBranch ? ' (atual)' : ''}</option>)}</select></label>
            <label className="date-field">Até a data<input type="date" value={cutoff} disabled={locked} onChange={event => {setCutoff(event.target.value); invalidate();}}/></label>
          </div>
          <div className="compare-actions"><p className="hint">Inclui o dia inteiro, no horário de Brasília. Commits já presentes ou equivalentes são excluídos.</p>
            <button className="primary" disabled={!allowed('PREPARE') || !repo || inputChanged || !source || !target || !cutoff || locked} onClick={prepare}>{busy ? 'Aguarde…' : 'Listar commits'}</button></div>
          {repo && target && !targetActive && <div className="target-notice"><span>Você está comparando com uma branch que não está ativa.</span>
            {allowed('SWITCH') && <button className="secondary small" disabled={locked || repo.dirtyTracked || Boolean(repo.operation)} onClick={() => setConfirmation({kind: 'switch', target})}>Ativar {target}</button>}</div>}
          <div className="table-toolbar"><div className="selection-summary"><strong>{plan?.commits.length ?? '—'}</strong> pendentes <span>·</span> <strong>{hashes.length}</strong> selecionados</div>
            <div className="actions"><button className="secondary small" disabled={!allowed('PREPARE') || !repo || inputChanged || !target || locked} onClick={() => fileInput.current.click()}>Importar TXT</button>
              <input ref={fileInput} type="file" accept=".txt,text/plain" className="hidden" onChange={importFile}/>
              <button className="secondary small" disabled={!allowed('PREPARE') || !matchesContext || !hashes.length || locked} onClick={exportFile}>Exportar TXT</button></div></div>
          <label className="search-label"><span className="sr-only">Filtrar commits</span><input value={search} disabled={!plan || running} onChange={event => setSearch(event.target.value)} placeholder="Filtrar por mensagem, data ou hash…"/></label>
          <div className="table-wrap">
            <table><thead><tr><th className="check-col"><input type="checkbox" aria-label="Selecionar commits visíveis" checked={allVisible} disabled={!visible.length || locked} onChange={event => setSelected(toggleVisible(selected, visible, event.target.checked))}/></th><th>Data</th><th>Commit</th><th>Hash</th></tr></thead>
              <tbody>{visible.map(commit => <tr key={commit.hash} className={selected.has(commit.hash) ? 'selected-row' : ''}>
                <td><input type="checkbox" aria-label={'Selecionar ' + commit.message} checked={selected.has(commit.hash)} disabled={locked} onChange={event => setSelected(toggleVisible(selected, [commit], event.target.checked))}/></td>
                <td className="date-cell" title={formatDate(commit.date, true)}>{formatDate(commit.date)}</td>
                <td className="message-cell">{commit.message}{progress.has(commit.hash) && <span className={'commit-state state-' + progress.get(commit.hash).state.toLowerCase()}>{displayState(progress.get(commit.hash).state)}</span>}</td>
                <td><code title={commit.hash}>{commit.hash.slice(0, 9)}</code></td>
              </tr>)}</tbody></table>
            {visible.length === 0 && <div className="empty-state"><BranchIcon/><strong>{plan ? 'Nenhum commit nesta lista' : 'Escolha as branches para começar'}</strong>
              <p>{plan ? (search ? 'Tente outro filtro.' : 'Não há alterações pendentes até a data escolhida.') : 'A prévia mostrará apenas os commits que ainda faltam no destino.'}</p></div>}
          </div>
          {plan && <p className="order-note">Ordem de aplicação: ancestrais primeiro. Prévia criada em {formatDate(plan.createdAt, true)}.</p>}
          {applyBlocked && <p id="apply-blocked" className="inline-warning" role="status">{applyBlocked}</p>}
          <div className="apply-toolbar"><div><strong>Revise antes de aplicar</strong><p>A simulação confere os commits sem alterar a branch.</p></div>
            <div className="actions"><button className="secondary" disabled={!allowed('SIMULATE') || !matchesContext || inputChanged || !hashes.length || locked} onClick={() => start(true)}>Simular</button>
              <button className="primary" disabled={!canApply} aria-describedby={applyBlocked ? "apply-blocked" : undefined} onClick={() => setConfirmation({kind: 'apply', target, count: hashes.length, repositoryPath: repo.path})}>Aplicar {hashes.length || ''} commits</button></div></div>
        </section>
      </div>

      <aside className="panel history-panel" aria-labelledby="history-title"><div className="section-heading"><div><span className="section-number">03</span><h2 id="history-title">Execuções</h2></div><span className="count-badge">{history.length}</span></div>
        <p className="hint">Histórico persistente de execuções autorizadas para este ambiente. Logs preservados no disco.</p>
        <div className="history-list">{history.map(item => <button key={item.id} className={'history-item ' + (item.id === run?.id ? 'active' : '')} disabled={locked} onClick={() => act(async () => setRun(await request('/runs/' + item.id)))}>
          <div><strong>{item.simulation ? 'Simulação' : 'Cherry-pick'}</strong><span className={'run-state state-' + item.state.toLowerCase()}>{displayState(item.state)}</span></div>
          <span className="history-branch">{item.targetBranch}</span><small>{formatDate(item.startedAt, true)} · {item.commits.length} commits</small>
        </button>)}</div>
        {history.length === 0 && <div className="history-empty">As simulações e aplicações aparecerão aqui.</div>}
        <div className="aside-note"><strong>Seu fluxo, com rastreabilidade</strong><p>Cada aplicação registra o hash original e o novo hash. Se houver conflito, os commits seguintes ficam aguardando.</p></div>
      </aside></div>

      <section className="panel log-section" aria-labelledby="log-title"><div className="section-heading"><div><span className="section-number">LOG</span><h2 id="log-title">Acompanhar execução</h2>
          {run && <span className={'run-state state-' + run.state.toLowerCase()}>{running && <i className="spinner"/>}{displayState(run.state)}</span>}</div>
        <div className="actions"><label className="follow-label"><input type="checkbox" checked={followLog} onChange={event => setFollowLog(event.target.checked)}/>Seguir log</label>
          <button className="secondary small" disabled={!run || busy} onClick={() => act(async () => downloadText('branch-control-' + run.id + '.log', await request('/runs/' + run.id + '/log')))}>Baixar log</button></div></div>
        {run?.state === 'CONFLICT' && <div className="conflict-help"><strong>A sequência foi interrompida por conflito.</strong>
          <p>Resolva os arquivos na IDE, execute <code>git add caminho/do/arquivo</code> e <code>git cherry-pick --continue</code>. Depois gere outra prévia para os commits restantes.</p>
          <p><code>git cherry-pick --abort</code> cancela somente o commit em andamento. Os anteriores já aplicados permanecem.</p></div>}
        <pre ref={logPanel} className="log-output" role="log" aria-label="Log da execução">{run ? run.lines.join('\n') : 'Aguardando uma simulação ou aplicação…'}</pre>
        {run && <div className="log-path"><span>Arquivo:</span><code>{run.logPath}</code></div>}
      </section>
      <footer><span>Branch Control · Ferramenta local</span><span>Datas em America/Sao_Paulo · Logs preservados no disco</span></footer>
    </main>
    <Confirmation value={confirmation} busy={busy} onCancel={() => setConfirmation(null)} onConfirm={confirm}/>
  </div>;
}
