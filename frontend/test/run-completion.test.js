import test from 'node:test';
import assert from 'node:assert/strict';
import {completeRun, keepPendingSelection, canOfferPush, sendConfirmedPush} from '../src/run-completion.js';

const applied = {id:'run', environmentId:'env', state:'COMPLETED', simulation:false,
  actor:{userId:'owner'}, commits:[{hash:'a',state:'APPLIED',newHash:'new-head'}]};

test('applying one of four retains three candidates using the new authoritative plan', async () => {
  const calls=[];
  const remaining={id:'new-plan',targetHead:'new-head',commits:[{hash:'b'},{hash:'c'},{hash:'d'}]};
  const api=async(path,options)=>{
    calls.push({path,options});
    if(path==='/runs/run/remaining')return remaining;
    if(path==='/runs')return [applied,{environmentId:'other'}];
    if(path==='/repositories/open')return {head:'new-head'};
    throw Error('Unexpected '+path);
  };
  const result=await completeRun(applied, {environmentId:'env'}, api);
  assert.equal(result.remaining,remaining);
  assert.equal(result.repository.head,remaining.targetHead);
  assert.equal(result.history.length,1);
  assert.deepEqual([...keepPendingSelection(new Set(['a','c']),remaining)],['c']);
  assert.equal(calls.some(call=>call.path==='/plans'),false);
  assert.equal(calls.find(call=>call.path.endsWith('/remaining')).options.method,'POST');
});

test('simulations retain the existing plan and do not offer or send push', async()=>{
  const calls=[];
  const api=async path=>{calls.push(path);return path==='/runs'?[]:{};};
  const result=await completeRun({...applied,simulation:true},{environmentId:'env',offerPush:true},api);
  assert.equal(result.remaining,undefined);
  assert.equal(result.pushOffer,null);
  assert.deepEqual(calls.sort(),['/repositories/open','/runs']);
});

test('push is only offered for successful real applications by an authorized owner or admin',()=>{
  const owner={id:'owner',group:'USER'};
  assert.equal(canOfferPush(applied,owner,true),true);
  assert.equal(canOfferPush(applied,{id:'another',group:'USER'},true),false);
  assert.equal(canOfferPush(applied,{id:'admin',group:'ADM'},true),true);
  assert.equal(canOfferPush(applied,owner,false),false);
  assert.equal(canOfferPush({...applied,state:'CONFLICT'},owner,true),false);
  assert.equal(canOfferPush({...applied,simulation:true},owner,true),false);
  assert.equal(canOfferPush({...applied,commits:[{state:'PRESENT'}]},owner,true),false);
});

test('a push offer reads the destination but never sends before confirmation',async()=>{
  const calls=[];
  const api=async(path,options)=>{calls.push({path,options});if(path==='/runs')return [applied];
    if(path.endsWith('/push-preview'))return {runId:'run',remote:'origin',targetBranch:'dest',head:'new-head'};
    return {};};
  const result=await completeRun(applied,{environmentId:'env',offerPush:true},api);
  assert.equal(result.pushOffer.remote,'origin');
  assert.equal(calls.some(call=>call.path.endsWith('/push')),false);
  await sendConfirmedPush(result.pushOffer,api);
  assert.deepEqual(calls.at(-1),{path:'/runs/run/push',options:{method:'POST',body:{expectedHead:'new-head'}}});
});

test('missing remote or refresh failure never discards a returned remaining plan',async()=>{
  const api=async path=>{
    if(path.endsWith('/remaining'))return {id:'remaining',commits:[{hash:'b'}]};
    if(path==='/runs')throw Error('History unavailable');
    if(path.endsWith('/push-preview'))throw Error('Configure origin');
    return {head:'new-head'};
  };
  const result=await completeRun(applied,{environmentId:'env',offerPush:true},api);
  assert.equal(result.remaining.commits.length,1);
  assert.equal(result.repository.head,'new-head');
  assert.equal(result.pushOffer,null);
  assert.match(result.warning,/History unavailable/);
  assert.match(result.warning,/Configure origin/);
});
