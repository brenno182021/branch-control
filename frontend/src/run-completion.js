import {isAdministrator} from './auth.js';
import {request} from './api.js';

export function keepPendingSelection(selected, remaining) {
  return new Set(remaining.commits.filter(commit => selected.has(commit.hash)).map(commit => commit.hash));
}

export function canOfferPush(run, user, allowed) {
  return Boolean(allowed && run && !run.simulation && run.state === 'COMPLETED' &&
    (isAdministrator(user) || run.actor?.userId === user?.id) &&
    run.commits.some(commit => commit.state === 'APPLIED'));
}

// Refreshes are independent: an unavailable report/remote must not discard pending candidates.
export async function completeRun(run, {environmentId, offerPush = false}, api = request) {
  const tasks = {
    history: () => api('/runs'),
    repository: () => api('/repositories/open', {method:'POST', body:{environmentId}}),
    ...(!run.simulation ? {remaining: () => api('/runs/' + run.id + '/remaining', {method:'POST'})} : {})
  };
  const entries = Object.entries(tasks);
  const results = await Promise.allSettled(entries.map(([,task]) => task()));
  const value = {pushOffer:null, warning:''};
  const errors = [];
  results.forEach((result,index) => {
    if (result.status === 'fulfilled') value[entries[index][0]] = result.value;
    else errors.push(result.reason?.message || 'Falha ao atualizar o estado da execução.');
  });
  // These endpoints acquire the same repository lock; finish renewal before previewing push.
  if (offerPush && !run.simulation && run.state === 'COMPLETED' && run.commits.some(commit => commit.state === 'APPLIED')) {
    try { value.pushOffer = await api('/runs/' + run.id + '/push-preview'); }
    catch (error) { errors.push(error.message || 'Não foi possível preparar o push.'); }
  }
  if (value.history) value.history = value.history.filter(item => item.environmentId === environmentId);
  value.warning = errors.join(' ');
  return value;
}

export function sendConfirmedPush(offer, api = request) {
  return api('/runs/' + offer.runId + '/push', {method:'POST', body:{expectedHead:offer.head}});
}
