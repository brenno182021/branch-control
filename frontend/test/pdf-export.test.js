import test from 'node:test';
import assert from 'node:assert/strict';
import {request, setSessionSecurity} from '../src/api.js';

test('PDF export preserves binary bytes and authenticated request headers', async()=>{
  const pdf=new Blob([new Uint8Array([37,80,68,70,45,0,255,128])],{type:'application/pdf'});
  let received;
  const fetcher=async(url,options)=>{
    received={url,options};
    return {ok:true,status:200,headers:{get:()=> 'application/pdf'},blob:async()=>pdf,
      text:async()=>{throw Error('PDF must not be decoded as text');}};
  };
  const result=await request('/reports.pdf?from=2026-10-30',{responseType:'blob'},fetcher);
  assert.equal(result,pdf);
  assert.equal(received.url,'/api/reports.pdf?from=2026-10-30');
  assert.equal(received.options.credentials,'same-origin');
  assert.equal(received.options.headers['X-Branch-Control'],'local');
  assert.equal(received.options.responseType,undefined);
});

test('PDF permission/session errors are shown instead of downloaded as a document',async()=>{
  let expired=false;setSessionSecurity('token',()=>{expired=true;});
  const fetcher=async()=>({ok:false,status:401,headers:{get:()=> 'application/json'},
    json:async()=>({message:'Entre para acessar os relatórios.'}),blob:async()=>{throw Error('Never download the error');}});
  await assert.rejects(request('/reports/blocked/pdf',{responseType:'blob'},fetcher),/Entre para acessar/);
  assert.equal(expired,true);setSessionSecurity(null,null);
});
