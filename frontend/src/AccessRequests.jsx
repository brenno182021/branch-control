import React,{useEffect,useState} from 'react';
import {request} from './api.js';
import {accessRequestError,approvalEnvironments} from './auth.js';
import {formatDate} from './helpers.js';

export function AccessRequestForm({onBack}) {
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[sent,setSent]=useState(false);
 async function submit(event) {
  event.preventDefault();const form=event.currentTarget,data=Object.fromEntries(new FormData(form));
  const validation=accessRequestError(data);if(validation){setError(validation);return;}
  setBusy(true);setError('');
  try {await request('/auth/access-requests',{method:'POST',body:{username:data.username.trim(),name:data.name.trim(),password:data.password}});form.reset();setSent(true);}
  catch(err){setError(err.message);}finally{setBusy(false);}
 }
 return <main className="auth-page"><section className="panel auth-card">
  <div className="eyebrow">BRANCH CONTROL</div><h1>Solicitar acesso</h1>
  {sent?<><p className="alert success" role="status">Solicitação enviada. Aguarde a aprovação de um responsável e depois entre com o login e a senha informados.</p><button className="secondary" onClick={onBack}>Voltar para entrar</button></>:
  <><p>Informe seus dados. Um responsável escolherá seu ambiente ao aprovar o acesso.</p>
  {error&&<p className="alert error" role="alert">{error}</p>}
  <form onSubmit={submit}><fieldset disabled={busy} className="access-request-fields">
   <label>Nome<input name="name" autoComplete="name" required maxLength={80}/></label>
   <label>Login<input name="username" autoComplete="username" required maxLength={40} aria-describedby="request-login-hint"/></label>
   <p className="hint" id="request-login-hint">De 2 a 40 letras sem acento, números, ponto, hífen ou sublinhado. Comece com letra ou número. Exemplo: nome.sobrenome.</p>
   <label>Senha<input name="password" type="password" autoComplete="new-password" required minLength={8} aria-describedby="request-password-hint"/></label>
   <p className="hint" id="request-password-hint">Mínimo de 8 caracteres e máximo de 72 bytes. Aceita maiúsculas, minúsculas e caracteres especiais.</p>
   <label>Confirmar senha<input name="confirmPassword" type="password" autoComplete="new-password" required minLength={8}/></label>
   <button className="primary">{busy?'Enviando…':'Solicitar acesso'}</button>
   <button type="button" className="secondary" onClick={onBack}>Voltar para entrar</button>
  </fieldset></form></>}
 </section></main>;
}

export default function AccessRequests({user,environments,onEnvironmentChanged,onQueueChanged}) {
 const [items,setItems]=useState([]),[selected,setSelected]=useState(null),[environmentId,setEnvironmentId]=useState(''),[reason,setReason]=useState(''),[error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false);
 const allowed=approvalEnvironments(user,environments);
 function updateQueue(rows){setItems(rows);onQueueChanged(rows.length);setSelected(previous=>rows.some(row=>row.id===previous?.id)?previous:null);}
 async function load(){updateQueue(await request('/access-requests'));}
 useEffect(()=>{let active=true;const reload=()=>request('/access-requests').then(rows=>{if(active)updateQueue(rows);}).catch(err=>{if(active){setItems([]);setSelected(null);setError(err.message);}});reload();const timer=setInterval(reload,30000);return()=>{active=false;clearInterval(timer);};},[]);
 function choose(item){setSelected(item);setEnvironmentId('');setReason('');setError('');setNotice('');}
 async function review(action) {
  if(!selected)return;
  if(action==='approve'&&!allowed.some(environment=>environment.id===environmentId)){setError('Selecione um ambiente autorizado e ativo para o novo usuário.');return;}
  setBusy(true);setError('');setNotice('');
  try {
   await request('/access-requests/'+selected.id+'/'+action,{method:'POST',body:action==='approve'?{environmentId}:{reason:reason.trim()}});
   const name=selected.username;setSelected(null);await load();
   if(action==='approve')await onEnvironmentChanged();
   setNotice(action==='approve'?'Acesso de '+name+' aprovado. O usuário já pode entrar.':'Solicitação de '+name+' rejeitada.');
  }catch(err){setError(err.message);}finally{setBusy(false);}
 }
 return <main className="auth-section"><h1>Solicitações de acesso</h1><p>Escolha um ambiente ao aprovar. A conta será criada com as permissões desse ambiente.</p>
  {error&&<p className="alert error" role="alert">{error}</p>}{notice&&<p className="alert success" role="status">{notice}</p>}
  <section className="panel"><div className="actions"><h2>Pendentes ({items.length})</h2><button className="secondary" disabled={busy} onClick={()=>{setError('');load().catch(err=>setError(err.message));}}>Atualizar</button></div>
  <div className="table-wrap"><table><thead><tr><th>Data</th><th>Nome</th><th>Login</th><th>Ação</th></tr></thead><tbody>{items.map(item=><tr key={item.id}><td>{formatDate(item.createdAt,true)}</td><td>{item.name}</td><td>{item.username}</td><td><button className="secondary small" disabled={busy} onClick={()=>choose(item)}>Analisar</button></td></tr>)}</tbody></table></div>{!items.length&&<p>Nenhuma solicitação pendente.</p>}</section>
  {selected&&<section className="panel"><h2>Analisar solicitação</h2><p><strong>{selected.name}</strong> · {selected.username}</p><fieldset className="access-review-fields" disabled={busy}>
   <label>Ambiente inicial<select value={environmentId} onChange={event=>setEnvironmentId(event.target.value)}><option value="">Selecione o ambiente</option>{allowed.map(environment=><option key={environment.id} value={environment.id}>{environment.name}</option>)}</select></label>
   {!allowed.length&&<p className="hint">Nenhum ambiente ativo disponível. Um ADM precisa cadastrar ou ativar um ambiente antes de aprovar.</p>}
   <p className="hint">A senha informada na solicitação não é exibida. A aprovação cria um usuário comum, sem acesso global de ADM.</p>
   {allowed.find(environment=>environment.id===environmentId)?.permissions.includes('APPROVE_USERS')&&<p className="hint">Este ambiente permite aprovar novos usuários. O novo membro também receberá essa permissão.</p>}
   <label>Motivo da rejeição (opcional, até 500 caracteres)<input value={reason} maxLength={500} onChange={event=>setReason(event.target.value)}/></label>
   <div className="actions"><button className="primary" disabled={!allowed.some(environment=>environment.id===environmentId)} onClick={()=>review('approve')}>{busy?'Salvando…':'Aprovar acesso'}</button><button className="secondary" onClick={()=>review('reject')}>Rejeitar</button><button className="secondary" onClick={()=>setSelected(null)}>Cancelar</button></div>
  </fieldset></section>}
 </main>;
}
