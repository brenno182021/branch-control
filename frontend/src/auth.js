export const isAdministrator = user => Boolean(user?.administrator || user?.group === 'ADM');
export const permissionLabels = {REPORTS:'Relatórios',PREPARE:'Preparar / importar / exportar',SIMULATE:'Simular',APPLY:'Aplicar',FETCH:'Atualizar refs (fetch)',SWITCH:'Trocar branch',APPROVE_USERS:'Aprovar novos usuários'};
export const hasPermission = (user, environment, permission) => isAdministrator(user) || Boolean(environment?.enabled && environment.permissions?.includes(permission));
export const canOperate = (user, environments = []) => isAdministrator(user) || environments.some(environment => environment.enabled && ['PREPARE','SIMULATE','APPLY','FETCH','SWITCH'].some(permission => hasPermission(user, environment, permission)));
export const canReport = (user, environments = []) => isAdministrator(user) || environments.some(environment => hasPermission(user, environment, 'REPORTS'));
export const approvalEnvironments = (user,environments=[]) => !user || user.enabled===false ? [] : environments.filter(environment=>environment.enabled && hasPermission(user,environment,'APPROVE_USERS'));
export const canApproveAccess = (user,environments=[]) => Boolean(user && user.enabled!==false && (isAdministrator(user) || approvalEnvironments(user,environments).length));
export const initialTab = (user,environments=[]) => canOperate(user,environments)?'operations':canReport(user,environments)?'reports':canApproveAccess(user,environments)?'access':'empty';
export function accessRequestError(form) {
 if (!/^[a-z0-9][a-z0-9._-]{1,39}$/.test((form.username||'').trim().toLowerCase())) return 'Use de 2 a 40 letras sem acento, números, ponto, hífen ou sublinhado no login. Comece com letra ou número.';
 if (!form.name?.trim() || form.name.trim().length>80) return 'Informe seu nome com até 80 caracteres.';
 const error=passwordError(form.password||''); if(error)return error;
 if(form.password!==form.confirmPassword)return 'As senhas não conferem.';
 return '';
}
export function hasExactSimulation(runs, user, planId, hashes, environment) {
 return Boolean(hashes.length && runs.some(run => run.simulation && run.state === 'COMPLETED' &&
 (!environment || (run.environmentId === environment.id)) &&
 run.actor?.userId === user?.id && run.planId === planId && run.commits?.length === hashes.length &&
 run.commits.every((commit,index) => commit.hash === hashes[index])));
}

export function passwordError(password) {
 if ([...password].length < 8) return 'A senha deve ter pelo menos 8 caracteres.';
 if (new TextEncoder().encode(password).length > 72) return 'A senha deve ter no máximo 72 bytes em UTF-8 (acentos ocupam mais de um byte).';
 return '';
}
