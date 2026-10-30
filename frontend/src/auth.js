export const isAdministrator = user => Boolean(user?.administrator || user?.group === 'ADM');
export const permissionLabels = {REPORTS:'Relatórios',PREPARE:'Preparar / importar / exportar',SIMULATE:'Simular',APPLY:'Aplicar',FETCH:'Atualizar refs (fetch)',SWITCH:'Trocar branch'};
export const hasPermission = (user, environment, permission) => isAdministrator(user) || Boolean(environment?.enabled && environment.permissions?.includes(permission));
export const canOperate = (user, environments = []) => isAdministrator(user) || environments.some(environment => environment.enabled && ['PREPARE','SIMULATE','APPLY','FETCH','SWITCH'].some(permission => hasPermission(user, environment, permission)));
export const canReport = (user, environments = []) => isAdministrator(user) || environments.some(environment => hasPermission(user, environment, 'REPORTS'));
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
