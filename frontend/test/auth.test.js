import test from 'node:test';
import assert from 'node:assert/strict';
import {canOperate, hasExactSimulation, passwordError} from '../src/auth.js';
import {request, setSessionSecurity} from '../src/api.js';
test('operations require administrator or enabled operational environment', () => {
 assert.equal(canOperate({group:'ADM'}),true);
 assert.equal(canOperate({group:'USER'},[{enabled:true,permissions:['PREPARE']}]),true);
 assert.equal(canOperate({group:'USER'},[{enabled:true,permissions:['REPORTS']}]),false);
 assert.equal(canOperate({group:'USER'},[{enabled:false,permissions:['APPLY']}]),false);
 assert.equal(canOperate({group:'unknown'}),false);
});
test('password follows length and UTF-8 byte limits without complexity rules', () => {
 assert.equal(passwordError('12345678'),'');
 assert.equal(passwordError('a'.repeat(72)),'');
 assert.notEqual(passwordError('a'.repeat(73)),'');
 assert.notEqual(passwordError('short'),'');
 assert.equal(passwordError('á'.repeat(36)),'');
 assert.notEqual(passwordError('á'.repeat(37)),'');
});
test('simulation belongs to actor and exact ordered plan selection', () => {
 const user={id:'u',group:'USER'}, run={actor:{userId:'u'},planId:'p',state:'COMPLETED',simulation:true,commits:[{hash:'a'},{hash:'b'}]};
 assert.equal(hasExactSimulation([run],user,'p',['a','b']),true);
 for (const [who,plan,hashes] of [[{id:'other'},'p',['a','b']],[user,'q',['a','b']],[user,'p',['b','a']],[user,'p',['a']]]) assert.equal(hasExactSimulation([run],who,plan,hashes),false);
 assert.equal(hasExactSimulation([{...run,state:'FAILED'}],user,'p',['a','b']),false);
});
test('API credentials, CSRF and expired session', async () => {
 let received, expired=false; setSessionSecurity('csrf',()=>{expired=true;});
 const fetcher=async (url,options)=>{received=options;return {ok:true,headers:{get:()=> 'application/json'},json:async()=>({})};};
 await request('/auth/logout',{method:'POST'},fetcher); assert.equal(received.credentials,'same-origin'); assert.equal(received.headers['X-CSRF-TOKEN'],'csrf');
 await request('/runs',{},fetcher); assert.equal(received.headers['X-CSRF-TOKEN'],undefined);
 await assert.rejects(request('/runs',{},async()=>({ok:false,status:401,headers:{get:()=> 'application/json'},json:async()=>({message:'Sessão expirada'})})),/Sessão expirada/);
 assert.equal(expired,true); setSessionSecurity(null,null);
});
