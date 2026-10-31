let csrfToken = null;
let onUnauthorized = null;
export function setSessionSecurity(token, handler) { csrfToken = token; onUnauthorized = handler; }

export async function request(path, options = {}, fetcher = globalThis.fetch) {
  const {body, headers, responseType, ...rest} = options;
  const response = await fetcher('/api' + path, {
    ...rest,
    credentials: 'same-origin',
    headers: {...headers, 'X-Branch-Control': 'local', ...(!['GET','HEAD'].includes((options.method || 'GET').toUpperCase()) && csrfToken ? {'X-CSRF-TOKEN': csrfToken} : {}), ...(body !== undefined ? {'Content-Type': 'application/json'} : {})},
    ...(body !== undefined ? {body: JSON.stringify(body)} : {})
  });
  const json = response.headers.get('content-type')?.includes('application/json');
  const payload = response.ok && responseType === 'blob' ? await response.blob() : json ? await response.json() : await response.text();
  if (response.status === 401) { csrfToken = null; if (path !== '/auth/session') onUnauthorized?.(); }
  if (!response.ok) throw new Error(typeof payload === 'object' && payload.message ? payload.message : 'Não foi possível concluir a operação. Confira o servidor e o log.');
  return payload;
}

export function downloadText(filename, content) {
  downloadFile(filename, new Blob([content], {type: 'text/plain;charset=utf-8'}));
}

export function downloadFile(filename, content) {
  const url = URL.createObjectURL(content);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
