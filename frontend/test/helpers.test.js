import test from 'node:test';
import assert from 'node:assert/strict';
import { selectedInPlanOrder, toggleVisible, isActiveRun, localDate, displayState, planMatchesContext } from '../src/helpers.js';
import { request } from '../src/api.js';

test('selection follows plan order instead of click order', () => {
  const commits = [{hash: 'a'}, {hash: 'b'}, {hash: 'c'}];
  assert.deepEqual(selectedInPlanOrder(commits, new Set(['c', 'a'])), ['a', 'c']);
});

test('select visible preserves selected commits outside the filter', () => {
  const selected = new Set(['outside']);
  assert.deepEqual([...toggleVisible(selected, [{hash: 'a'}, {hash: 'b'}], true)], ['outside', 'a', 'b']);
  assert.deepEqual([...toggleVisible(new Set(['outside', 'a']), [{hash: 'a'}], false)], ['outside']);
  assert.deepEqual([...selected], ['outside']);
});

test('completed and conflict jobs stop polling and enable recovery', () => {
  assert.equal(isActiveRun({state: 'RUNNING'}), true);
  assert.equal(isActiveRun({state: 'QUEUED'}), true);
  assert.equal(isActiveRun({state: 'CONFLICT'}), false);
  assert.equal(isActiveRun({state: 'COMPLETED'}), false);
  assert.equal(isActiveRun(null), false);
  assert.equal(displayState('EQUIVALENT'), 'Já aplicado (equivalente)');
  assert.equal(displayState('INTERRUPTED'), 'Interrompido');
});

test('local date does not shift the chosen calendar day to UTC', () => {
  assert.equal(localDate(new Date(2026, 9, 29, 23, 59, 0)), '2026-10-29');
});

test('a preview from another repository or branch cannot enable application', () => {
  const preview = {repositoryPath: 'C:/workspace/A', sourceBranch: 'refs/heads/draft',
    targetBranch: 'destino', cutoff: '2026-10-29'};
  assert.equal(planMatchesContext(preview, {path: 'C:/workspace/A'}, 'refs/heads/draft', 'destino', '2026-10-29'), true);
  assert.equal(planMatchesContext(preview, {path: 'C:/workspace/B'}, 'refs/heads/draft', 'destino', '2026-10-29'), false);
  assert.equal(planMatchesContext(preview, {path: 'C:/workspace/A'}, 'refs/remotes/origin/draft', 'destino', '2026-10-29'), false);
  assert.equal(planMatchesContext(preview, {path: 'C:/workspace/A'}, 'refs/heads/draft', 'outra', '2026-10-29'), false);
  assert.equal(planMatchesContext(preview, {path: 'C:/workspace/A'}, 'refs/heads/draft', 'destino', '2026-10-28'), false);
});

test('a refreshed HEAD cannot authorize applying an old preview', () => {
  const preview={repositoryPath:'repo',sourceBranch:'draft',targetBranch:'dest',cutoff:'2026-10-30',targetHead:'before'};
  assert.equal(planMatchesContext(preview,{path:'repo',head:'after'},'draft','dest','2026-10-30'),false);
  assert.equal(planMatchesContext({...preview,targetHead:'after'},{path:'repo',head:'after'},'draft','dest','2026-10-30'),true);
});

test('API failures preserve the server message and all calls identify the local client', async () => {
  let received;
  const fetcher = async (url, options) => {
    received = {url, options};
    return {ok: false, status: 409, headers: {get: () => 'application/json'},
      json: async () => ({message: 'O HEAD mudou depois da prévia.'})};
  };
  await assert.rejects(() => request('/plans', {method: 'POST', body: {cutoff: '2026-10-29'}}, fetcher),
    /O HEAD mudou depois da prévia/);
  assert.equal(received.options.headers['X-Branch-Control'], 'local');
  assert.equal(received.options.headers['Content-Type'], 'application/json');
  assert.equal(received.url, '/api/plans');
  assert.equal(JSON.parse(received.options.body).cutoff, '2026-10-29');
});
