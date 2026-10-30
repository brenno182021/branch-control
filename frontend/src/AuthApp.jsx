import React, {useCallback, useEffect, useState} from 'react';
import App from './App.jsx';
import Environments from './Environments.jsx';
import AccessRequests,{AccessRequestForm} from './AccessRequests.jsx';
import {request, downloadText, setSessionSecurity} from './api.js';
import {canOperate, canReport, canApproveAccess, initialTab, isAdministrator, passwordError} from './auth.js';
import {displayState, formatDate} from './helpers.js';

function Login({session, refresh}) {
 const [error,setError]=useState(''), [busy,setBusy]=useState(false),[requestAccess,setRequestAccess]=useState(false);
 const setup=session.setupRequired;
 async function submit(event) {
  event.preventDefault(); setBusy(true);setError('');
  const data=Object.fromEntries(new FormData(event.currentTarget));
  try { if (setup && passwordError(data.password)) throw new Error(passwordError(data.password)); await request(setup?'/auth/setup':'/auth/login',{method:'POST',body:data}); await refresh(); }
  catch(err) {setError(err.message);} finally {setBusy(false);}
 }
 if(requestAccess&&!setup)return <AccessRequestForm onBack={()=>setRequestAccess(false)}/>;
 return <main className="auth-page"><section className="panel auth-card"><div className="eyebrow">BRANCH CONTROL</div><h1>{setup?'Criar primeiro administrador':'Entrar'}</h1>
 {error && <p className="alert error" role="alert">{error}</p>}
 {setup && !session.setupAllowed ? <p>Abra esta aplicação em localhost no computador do servidor para criar o primeiro administrador.</p> : <form onSubmit={submit}>
 <label>Usuário<input name="username" required autoComplete="username" maxLength={40}/></label>
 {setup && <label>Nome<input name="name" required autoComplete="name" maxLength={80}/></label>}
 <label>Senha<input name="password" type="password" required minLength={setup?8:undefined} aria-describedby={setup?'setup-password-hint':undefined} autoComplete={setup?'new-password':'current-password'}/></label>
 {setup && <p id="setup-password-hint" className="hint">Senha com pelo menos 8 caracteres e no máximo 72 bytes em UTF-8. Acentos ocupam mais de um byte.</p>}
 <button className="primary" disabled={busy}>{busy?'Aguarde…':setup?'Criar administrador':'Entrar'}</button></form>}
 {!setup&&<div className="access-request-entry"><p>Ainda não tem usuário?</p><button className="secondary" disabled={busy} onClick={()=>{setError('');setRequestAccess(true);}}>Solicitar acesso</button></div>}
 </section></main>;
}

function Reports({environments}) {
 const [filters,setFilters]=useState({from:'',to:'',state:'',environmentId:''}), [report,setReport]=useState(null), [selected,setSelected]=useState(null), [log,setLog]=useState(''), [error,setError]=useState(''), [busy,setBusy]=useState(false);
 const query=new URLSearchParams(Object.entries(filters).filter(([,value])=>value)).toString();
 async function load(event) {event?.preventDefault();setBusy(true);setError('');try {setReport(await request('/reports?'+query));} catch(err){setError(err.message);}finally{setBusy(false);}}
 useEffect(()=>{load();},[]);
 async function show(run) {setBusy(true);setError('');setSelected(null);setLog('');try {const [view,text]=await Promise.all([request('/runs/'+run.id),request('/runs/'+run.id+'/log')]);setSelected(view);setLog(text);}catch(err){setError(err.message);}finally{setBusy(false);}}
 return <main className="auth-section"><h1>Relatórios e histórico</h1><p>Execuções permitidas pelos seus ambientes.</p>{error&&<p role="alert" className="alert error">{error}</p>}
 <form className="panel auth-filters" onSubmit={load}>
 <label>Ambiente<select value={filters.environmentId} onChange={e=>{setFilters({...filters,environmentId:e.target.value});setReport(null);setSelected(null);setLog('');}}><option value="">Todos os autorizados</option>{environments.filter(item=>item.enabled).map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
 <label>De<input type="date" value={filters.from} onChange={e=>setFilters({...filters,from:e.target.value})}/></label>
 <label>Até<input type="date" min={filters.from} value={filters.to} onChange={e=>setFilters({...filters,to:e.target.value})}/></label>
 <label>Resultado<select value={filters.state} onChange={e=>setFilters({...filters,state:e.target.value})}><option value="">Todos</option>{['QUEUED','RUNNING','COMPLETED','FAILED','CONFLICT','INTERRUPTED'].map(s=><option key={s} value={s}>{displayState(s)}</option>)}</select></label>
 <button className="primary" disabled={busy}>Filtrar</button><button type="button" className="secondary" disabled={busy} onClick={async()=>{setBusy(true);setError('');try{downloadText('branch-control-relatorios.csv',await request('/reports.csv?'+query));}catch(err){setError(err.message);}finally{setBusy(false);}}}>Exportar CSV</button></form>
 {report&&<><p role="status">{report.total} execuções · {report.applied} commits aplicados · {report.simulations} simulações · {report.failed} falhas</p><div className="panel table-wrap"><table><thead><tr><th>Data</th><th>Usuário / grupo</th><th>Ambiente / operação / destino</th><th>Resultado</th><th>Detalhes</th></tr></thead><tbody>{report.runs.map(run=><tr key={run.id}><td>{formatDate(run.startedAt,true)}</td><td>{run.actor?.username || '—'} / {run.actor?.group || '—'}</td><td>{run.environmentName || '—'} · {run.simulation?'Simulação':'Aplicação'} · {run.targetBranch}</td><td>{displayState(run.state)}</td><td><button className="secondary small" disabled={busy} onClick={()=>show(run)}>Ver hashes e log</button></td></tr>)}</tbody></table>{!report.runs.length&&<p>Nenhuma execução para os filtros.</p>}</div></>}
 {selected&&<section className="panel"><h2>Execução {selected.id}</h2><p>{selected.environmentName} · {selected.actor?.username} · {selected.actor?.group} · {displayState(selected.state)}</p><div className="table-wrap"><table><thead><tr><th>Original</th><th>Novo hash</th><th>Resultado</th></tr></thead><tbody>{selected.commits.map(commit=><tr key={commit.hash}><td><code>{commit.hash}</code></td><td><code>{commit.newHash || '—'}</code></td><td>{displayState(commit.state)}</td></tr>)}</tbody></table></div><h3>Log</h3><pre className="log-output" role="log">{log}</pre><button className="secondary" onClick={()=>downloadText('branch-control-'+selected.id+'.log',log)}>Baixar log</button></section>}
 </main>;
}

function Users() {
 const [users,setUsers]=useState([]), [network,setNetwork]=useState(null), [editing,setEditing]=useState(null), [form,setForm]=useState({username:'',name:'',password:'',group:'USER',enabled:true}), [error,setError]=useState(''), [notice,setNotice]=useState(''), [busy,setBusy]=useState(false);
 async function load() {const [items,info]=await Promise.all([request('/admin/users'),request('/admin/network')]);setUsers(items);setNetwork(info);}
 useEffect(()=>{load().catch(err=>setError(err.message));},[]);
 function edit(user) {setEditing(user.id);setForm({username:user.username,name:user.name,password:'',group:user.group,enabled:user.enabled});setNotice('');}
 function reset(){setEditing(null);setForm({username:'',name:'',password:'',group:'USER',enabled:true});}
 async function save(event) {event.preventDefault();setBusy(true);setError('');setNotice('');try{if(form.password && passwordError(form.password)) throw new Error(passwordError(form.password));const body={name:form.name.trim(),group:form.group,enabled:form.enabled,...(form.password?{password:form.password}:{})};if(!editing)body.username=form.username.trim();await request('/admin/users'+(editing?'/'+editing:''),{method:editing?'PUT':'POST',body});reset();await load();setNotice('Usuário salvo.');}catch(err){setError(err.message);}finally{setBusy(false);}}
 return <main className="auth-section"><h1>Usuários</h1>{error&&<p className="alert error" role="alert">{error}</p>}{notice&&<p className="alert success" role="status">{notice}</p>}
 <section className="panel"><h2>{editing?'Editar usuário':'Criar usuário'}</h2><form className="auth-user-form" onSubmit={save}>
 <label>Usuário<input value={form.username} disabled={Boolean(editing)||busy} required autoComplete="off" maxLength={40} onChange={e=>setForm({...form,username:e.target.value})}/></label>
 <label>Nome<input value={form.name} required disabled={busy} maxLength={80} onChange={e=>setForm({...form,name:e.target.value})}/></label>
 <label>Papel<select value={form.group} disabled={busy} onChange={e=>setForm({...form,group:e.target.value})}>{['USER','ADM'].map(group=><option key={group} value={group}>{group==='ADM'?'Administrador':'Usuário'}</option>)}</select></label>
 <label>{editing?'Nova senha (deixe vazia para manter)':'Senha'}<input type="password" value={form.password} required={!editing} minLength={8} disabled={busy} aria-describedby="user-password-hint" autoComplete="new-password" onChange={e=>setForm({...form,password:e.target.value})}/></label>
 <p id="user-password-hint" className="hint">Senha com pelo menos 8 caracteres e no máximo 72 bytes em UTF-8. Acentos ocupam mais de um byte.</p>{editing&&<label className="auth-check"><input type="checkbox" checked={form.enabled} disabled={busy} onChange={e=>setForm({...form,enabled:e.target.checked})}/>Ativo</label>}
 <div className="actions"><button className="primary" disabled={busy}>Salvar usuário</button>{editing&&<button type="button" className="secondary" disabled={busy} onClick={reset}>Cancelar edição</button>}</div></form></section>
 <section className="panel table-wrap"><table><thead><tr><th>Usuário</th><th>Nome</th><th>Papel</th><th>Situação</th><th>Ação</th></tr></thead><tbody>{users.map(user=><tr key={user.id}><td>{user.username}</td><td>{user.name}</td><td>{isAdministrator(user)?'Administrador':'Usuário'}</td><td>{user.enabled?'Ativo':'Inativo'}</td><td><button className="secondary small" disabled={busy} onClick={()=>edit(user)}>Editar</button></td></tr>)}</tbody></table></section>
 {network&&<section className="panel"><h2>Acesso pela rede</h2><ul>{network.urls?.map(url=><li key={url}><a href={url}>{url}</a></li>)}</ul><p>Administradores têm acesso global. Configure os acessos dos demais usuários na tela Ambientes.</p></section>}
 </main>;
}

export default function AuthApp() {
 const [session,setSession]=useState(null), [environments,setEnvironments]=useState([]), [error,setError]=useState(''), [tab,setTab]=useState('operations'), [locked,setLocked]=useState(false), [busy,setBusy]=useState(false),[pendingCount,setPendingCount]=useState(0);
 const expire=useCallback(()=>{setSession(previous => previous ? {...previous, authenticated:false, user:null} : previous);setSessionSecurity(null,expire);refresh();},[]);
 async function refresh(){try{const next=await request('/auth/session');setSessionSecurity(next.csrfToken,expire);setSession(next);setError('');const items=next.authenticated?await request('/environments'):[];setEnvironments(items);setTab(initialTab(next.user,items));}catch(err){setError(err.message);}}
 useEffect(()=>{setSessionSecurity(null,expire);refresh();return()=>setSessionSecurity(null,null);},[]);
 const approver=canApproveAccess(session?.user,environments);
 useEffect(()=>{if(!session?.authenticated||!approver){setPendingCount(0);return;}if(tab==='access')return;let active=true;const poll=()=>request('/access-requests').then(rows=>{if(active)setPendingCount(rows.length);}).catch(()=>{if(active)setPendingCount(0);});poll();const timer=setInterval(poll,30000);return()=>{active=false;clearInterval(timer);};},[session?.authenticated,session?.user?.id,approver,environments,tab]);
 async function logout(){setBusy(true);try{await request('/auth/logout',{method:'POST'});setSession(null);await refresh();}catch(err){setError(err.message);}finally{setBusy(false);}}
 if(!session)return <main className="auth-page"><section className="panel"><p role="status">{error||'Carregando sessão…'}</p>{error&&<button onClick={refresh}>Tentar novamente</button>}</section></main>;
 if(!session.authenticated)return <Login session={session} refresh={refresh}/>;
 const user=session.user;
 return <div className="auth-shell"><header className="topbar"><div className="brand"><div><strong>Branch Control</strong><span>Controle de branches e execuções</span></div></div><div className="auth-account"><span>{user.name || user.username} · {user.group}</span><button className="secondary small" disabled={locked||busy} onClick={logout}>Sair</button></div></header>
 <nav className="auth-nav" aria-label="Navegação principal">{[...(canOperate(user,environments)?[['operations','Operações']]:[]),...(canReport(user,environments)?[['reports','Relatórios']]:[]),...(canApproveAccess(user,environments)?[['access','Solicitações de acesso'+(pendingCount?' ('+pendingCount+')':'')]]:[]),...(isAdministrator(user)?[['users','Usuários'],['environments','Ambientes']]:[])].map(([id,label])=><button key={id} className={tab===id?'primary':'secondary'} aria-current={tab===id?'page':undefined} disabled={locked && ['users','environments','access'].includes(id)} onClick={()=>setTab(id)}>{label}</button>)}{locked&&<span role="status">Operação em andamento; ambiente e sessão bloqueados.</span>}</nav>
 {error&&<p role="alert" className="alert error">{error}</p>}
 {canOperate(user,environments)&&<div hidden={tab!=='operations'}><App key={user.id} user={user} environments={environments} onLockChange={setLocked} onCreateEnvironment={()=>setTab('environments')}/></div>}
 {tab==='reports'&&<Reports environments={environments.filter(item=>isAdministrator(user)||item.permissions.includes('REPORTS'))}/>}{tab==='users'&&isAdministrator(user)&&<Users/>}
 {tab==='environments'&&isAdministrator(user)&&<Environments onSaved={async()=>setEnvironments(await request('/environments'))}/>}
 {tab==='access'&&canApproveAccess(user,environments)&&<AccessRequests user={user} environments={environments} onQueueChanged={setPendingCount} onEnvironmentChanged={async()=>setEnvironments(await request('/environments'))}/>}
 {tab==='empty'&&<main className="auth-section panel"><h1>Nenhum acesso disponível</h1><p>Solicite a um administrador acesso a um ambiente.</p></main>}</div>;
}
