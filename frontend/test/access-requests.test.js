import test from 'node:test';
import assert from 'node:assert/strict';
import {approvalEnvironments,canApproveAccess,initialTab,accessRequestError} from '../src/auth.js';

test('approver can assign only enabled environments with approval permission',()=>{
 const user={group:'USER',enabled:true};
 const envs=[{id:'allowed',enabled:true,permissions:['APPROVE_USERS']},{id:'reports',enabled:true,permissions:['REPORTS']},{id:'disabled',enabled:false,permissions:['APPROVE_USERS']}];
 assert.deepEqual(approvalEnvironments(user,envs).map(e=>e.id),['allowed']);
 assert.deepEqual(approvalEnvironments({group:'ADM',enabled:true},envs).map(e=>e.id),['allowed','reports']);
 assert.deepEqual(approvalEnvironments(null,envs),[]);
 assert.deepEqual(approvalEnvironments({...user,enabled:false},envs),[]);
 assert.equal(canApproveAccess(user,envs),true);
 assert.equal(canApproveAccess(user,[envs[1]]),false);
 assert.equal(canApproveAccess({group:'ADM',enabled:true},[]),true);
});

test('approval-only user reaches request queue without Git operation permissions',()=>{
 const user={group:'USER',enabled:true},env={enabled:true,permissions:['APPROVE_USERS']};
 assert.equal(initialTab(user,[env]),'access');
 assert.equal(initialTab(user,[]),'empty');
 assert.equal(initialTab(user,[{...env,permissions:['REPORTS']}]),'reports');
 assert.equal(initialTab(user,[{...env,permissions:['PREPARE','APPROVE_USERS']}]),'operations');
});

test('request validates login separately and accepts mixed case and special password',()=>{
 const form={username:'Novo.Usuario',name:'Pessoa',password:'Aa1!@#çTeste',confirmPassword:'Aa1!@#çTeste'};
 assert.equal(accessRequestError(form),'');
 assert.match(accessRequestError({...form,username:'email@example.com'}),/login/);
 assert.match(accessRequestError({...form,confirmPassword:'diferente'}),/conferem/);
 assert.match(accessRequestError({...form,password:'curta',confirmPassword:'curta'}),/8/);
 assert.match(accessRequestError({...form,name:' '}),/nome/);
});
