import test from 'node:test';
import assert from 'node:assert/strict';
import {validateToolArguments} from '@earendil-works/pi-ai';
import {nativeTools} from '../src/tools.js';

test('notification reads and calendar queries never request a virtual display',async()=>{
 let calls:string[]=[];let acquisitions=0;
 const host={
  ensureDisplay:async()=>{acquisitions++;return {status:'acquired'};},
  call:(_method:string,args:any)=>{calls.push(args.name);return {status:'ok'};},
 };
 const all=nativeTools(host,'api-worker');
 for(const name of ['phone_calendar_events','phone_calendars','phone_notification_read','phone_notifications','phone_contacts']) {
  const args=name==='phone_calendar_events'?{start_ms:10,end_ms:20}:name==='phone_contacts'?{query:'Test'}:{};
  const tool=all.find((t:any)=>t.name===name)!;
  assert.ok(tool,name);
  assert.deepEqual(validateToolArguments(tool,{type:'toolCall',id:name,name,arguments:args}),args);
  assert.equal((await tool.execute(name,args)).isError,false);
 }
 const notification=all.find((t:any)=>t.name==='phone_notification_read')!;
 assert.deepEqual(validateToolArguments(notification,{type:'toolCall',id:'page',name:'phone_notification_read',arguments:{offset:2147483647,limit:1}}),{offset:2147483647,limit:1});
 assert.equal(acquisitions,0);
 assert.deepEqual(calls,['phone_calendar_events','phone_calendars','phone_notification_read','phone_notifications','phone_contacts']);
});

test('URL and Clock intents use private-display lease and mutation IDs',async()=>{
 let acquired=0,dispatched=0;
 const host={
  ensureDisplay:async()=>{acquired++;return {status:'acquired'};},
  call:(_method:string,args:any)=>{
   assert.ok(['phone_open_url','phone_clock_screen'].includes(args.name));
   assert.equal(args.args.operation_id,args.name==='phone_open_url'?op:op+'_clock');
   dispatched++;return {status:'requested'};
  },
 };
 const all=nativeTools(host,'ui-worker');
 const op='123456789-abcdef1234567890_open_url001';
 const url=all.find((x:any)=>x.name==='phone_open_url')!;
 const clock=all.find((x:any)=>x.name==='phone_clock_screen')!;
 assert.ok(url&&clock);
 assert.deepEqual(validateToolArguments(url,{type:'toolCall',id:'u',name:url.name,arguments:{url:'https://example.org',operation_id:op}}),{url:'https://example.org',operation_id:op});
 assert.deepEqual(validateToolArguments(url,{type:'toolCall',id:'short',name:url.name,arguments:{url:'http://a',operation_id:op}}),{url:'http://a',operation_id:op});
 assert.throws(()=>validateToolArguments(url,{type:'toolCall',id:'u',name:url.name,arguments:{url:'https://example.org'}}),/operation_id/);
 assert.throws(()=>validateToolArguments(clock,{type:'toolCall',id:'c',name:clock.name,arguments:{screen:'emails',operation_id:op}}),/screen/);
 await url.execute('u',{url:'https://example.org',operation_id:op});
 await clock.execute('c',{screen:'alarms',operation_id:op+'_clock'});
 assert.equal(acquired,2);assert.equal(dispatched,2);
});


test('physical display UI calls bypass the virtual-display lease',async()=>{
 let acquisitions=0;const calls:any[]=[];
 const host={
  ensureDisplay:async()=>{acquisitions++;return {status:'acquired',display_id:77};},
  releaseScreen:()=>{},
  dropPriorImages:()=>{},
  call:(_method:string,args:any)=>{
   calls.push(args);
   if(args.name==='phone_observe')return {snapshot_id:'physical-1',package_name:'dev.pocket.agent.fixture',window_id:1,nodes:[],display_target:'physical',display_id:0};
   return {status:'requested',package_name:args.args?.package_name,display_target:args.args?.display_target,display_id:0};
  },
 };
 const all=nativeTools(host,'visible-worker');
 const op='123456789-abcdef1234567890_visible01';
 const open=all.find((x:any)=>x.name==='phone_open_app')!;
 const observe=all.find((x:any)=>x.name==='phone_observe')!;
 assert.deepEqual(validateToolArguments(open,{type:'toolCall',id:'open',name:open.name,arguments:{package_name:'dev.pocket.agent.fixture',display_target:'physical',operation_id:op}}),
  {package_name:'dev.pocket.agent.fixture',display_target:'physical',operation_id:op});
 await open.execute('open',{package_name:'dev.pocket.agent.fixture',display_target:'physical',operation_id:op});
 await observe.execute('observe',{display_target:'physical'});
 assert.equal(acquisitions,0);
 assert.equal(calls.length,2);
 assert.equal(calls[0].args.display_target,'physical');
 assert.equal(calls[1].args.display_target,'physical');

 await open.execute('private',{package_name:'dev.pocket.agent.fixture',operation_id:op+'_private'});
 assert.equal(acquisitions,1,'default virtual UI behavior must remain unchanged');
});

test('mixed-target batch rejects before reserving a virtual display',async()=>{
 let acquisitions=0,dispatched=0;
 const host={
  ensureDisplay:async()=>{acquisitions++;return {status:'acquired'};},
  call:()=>{dispatched++;return {status:'unexpected'};},
 };
 const batch=nativeTools(host,'mixed-batch').find((x:any)=>x.name==='phone_batch')!;
 const op='123456789-abcdef1234567890_mixed001';
 const result=await batch.execute('mixed',{calls:[
  {name:'phone_open_app',args:{package_name:'dev.pocket.agent.fixture',display_target:'physical',operation_id:op}},
  {name:'phone_observe',args:{display_target:'virtual'}},
 ]});
 assert.equal(result.isError,true);
 assert.equal(result.details.code,'INVALID_ARGUMENT');
 assert.equal(acquisitions,0);
 assert.equal(dispatched,0);
});

test('physical-only batch does not reserve a virtual display',async()=>{
 let acquisitions=0,dispatched=0;
 const host={
  ensureDisplay:async()=>{acquisitions++;return {status:'acquired'};},
  dropPriorImages:()=>{},
  call:(_method:string,args:any)=>{assert.equal(args.name,'phone_batch');dispatched++;return {status:'completed',results:[]};},
 };
 const batch=nativeTools(host,'visible-batch').find((x:any)=>x.name==='phone_batch')!;
 const op='123456789-abcdef1234567890_batchvis1';
 const args={calls:[
  {name:'phone_open_app',args:{package_name:'dev.pocket.agent.fixture',display_target:'physical',operation_id:op}},
  {name:'phone_observe',args:{display_target:'physical'}},
 ]};
 await batch.execute('batch',args);
 assert.equal(dispatched,1);
 assert.equal(acquisitions,0);
});

test('an approval expiring while a UI tool waits for a display prevents native mutation',async()=>{
  let expired=false,dispatches=0;
  const host:any={
    authorizePhone:()=>{if(expired)throw Error('Approval expired');},
    ensureDisplay:async()=>{expired=true;return {status:'acquired'};},
    call:()=>{dispatches++;return {status:'unexpected'};},
  };
  const tool=nativeTools(host,'automated').find(tool=>tool.name==='phone_act')!;
  await assert.rejects(()=>tool.execute('expired-during-wait',{kind:'click',snapshot_id:'snap',selector:{text:'Send'},operation_id:'fixture_operation_0001'}),/Approval expired/);
  assert.equal(dispatches,0);
});

test('everyday direct controls use native APIs and settings requires an explicit physical target',async()=>{
 let acquisitions=0;const calls:any[]=[];
 const tools=nativeTools({ensureDisplay:async()=>{acquisitions++;return {};},call:(_method:string,args:any)=>{calls.push(args);return {status:'verified'};}},'quick-controls');
 const op='20741-0123456789abcdef_quick_controls_0001';
 for(const [name,args] of [
  ['phone_device_info',{}],['phone_volume',{action:'set',percent:35,operation_id:op}],
  ['phone_brightness',{percent:60,operation_id:op}],['phone_flashlight',{enabled:true,operation_id:op}],
  ['phone_settings',{screen:'wifi',display_target:'physical',operation_id:op}],
 ] as const) {
  const tool=tools.find(t=>t.name===name)!;
  assert.deepEqual(validateToolArguments(tool,{type:'toolCall',id:name,name,arguments:args}),args);
  await tool.execute(name,args);
 }
 assert.equal(acquisitions,0);
 assert.equal(calls.length,5);
 assert.deepEqual(calls.map(c=>c.name),['phone_device_info','phone_volume','phone_brightness','phone_flashlight','phone_settings']);
 const settings=tools.find(t=>t.name==='phone_settings')!;
 assert.throws(()=>validateToolArguments(settings,{type:'toolCall',id:'missing',name:settings.name,arguments:{screen:'wifi',operation_id:op}}),/display_target/);
 assert.throws(()=>validateToolArguments(settings,{type:'toolCall',id:'virtual',name:settings.name,arguments:{screen:'wifi',display_target:'virtual',operation_id:op}}),/display_target/);
 const volume=tools.find(t=>t.name==='phone_volume')!;
 assert.throws(()=>validateToolArguments(volume,{type:'toolCall',id:'loud',name:volume.name,arguments:{action:'set',percent:101,operation_id:op}}),/percent/);
});
