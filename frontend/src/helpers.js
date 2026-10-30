export function selectedInPlanOrder(commits, selected) {
  return commits.filter(commit => selected.has(commit.hash)).map(commit => commit.hash);
}

export function toggleVisible(selected, visible, checked) {
  const next = new Set(selected);
  for (const commit of visible) checked ? next.add(commit.hash) : next.delete(commit.hash);
  return next;
}

export function isActiveRun(run) {
  return Boolean(run && ['QUEUED', 'RUNNING'].includes(run.state));
}

export function planMatchesContext(plan, repo, source, target, cutoff, environment) {
  return Boolean(plan && repo && (!environment || (plan.environmentId === environment.id && plan.environmentRevision === environment.revision)) && plan.repositoryPath === repo.path &&
    plan.sourceBranch === source && plan.targetBranch === target && plan.cutoff === cutoff);
}

export function localDate(date = new Date()) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
}

const labels = {
  INTERRUPTED: 'Interrompido', QUEUED: 'Na fila', RUNNING: 'Em execução', COMPLETED: 'Concluído', FAILED: 'Falhou',
  CONFLICT: 'Conflito', PENDING: 'Pendente', PRESENT: 'Já presente',
  EQUIVALENT: 'Já aplicado (equivalente)', WILL_APPLY: 'Será aplicado', APPLIED: 'Aplicado'
};

export function displayState(state) { return labels[state] || state; }

export function formatDate(value, withTime = false) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', year: 'numeric',
    ...(withTime ? {hour: '2-digit', minute: '2-digit', second: '2-digit'} : {})
  }).format(date);
}
