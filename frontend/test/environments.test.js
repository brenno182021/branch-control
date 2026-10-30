import test from 'node:test';
import assert from 'node:assert/strict';
import {hasPermission,canReport,hasExactSimulation} from '../src/auth.js';
import {planMatchesContext} from '../src/helpers.js';
const user={id:'u',group:'USER'}, environment={id:'e',revision:2,enabled:true,permissions:['REPORTS']};
test('permissions are granted per enabled environment with administrator bypass',()=>{
 assert.equal(hasPermission(user,environment,'REPORTS'),true);
 assert.equal(hasPermission(user,environment,'APPLY'),false);
 assert.equal(hasPermission(user,{...environment,enabled:false},'REPORTS'),false);
 assert.equal(hasPermission({administrator:true},environment,'APPLY'),true);
 assert.equal(canReport(user,[environment]),true);
 assert.equal(canReport(user,[{...environment,permissions:['PREPARE']}]),false);
});
test('preview context rejects another environment and revised access rules',()=>{
 const plan={environmentId:'e',environmentRevision:2,repositoryPath:'repo',sourceBranch:'refs/heads/source',targetBranch:'target',cutoff:'2026-10-30'};
 const matches=env=>planMatchesContext(plan,{path:'repo'},'refs/heads/source','target','2026-10-30',env);
 assert.equal(matches(environment),true);
 assert.equal(matches({...environment,id:'other'}),false);
 assert.equal(matches({...environment,revision:3}),false);
});
test('simulation must belong to environment as well as actor, plan and ordered selection',()=>{
 const run={environmentId:'e',actor:{userId:'u'},planId:'p',simulation:true,state:'COMPLETED',commits:[{hash:'a'}]};
 assert.equal(hasExactSimulation([run],user,'p',['a'],environment),true);
 assert.equal(hasExactSimulation([{...run,environmentId:'other'}],user,'p',['a'],environment),false);
});
