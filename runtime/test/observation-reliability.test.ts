import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeTools,toolResult} from '../src/tools.js';

const crowdedNodes=()=>Array.from({length:150},(_,i)=>({index:i,text:('Long \\"quoted\\" text with \\slashes and emoji 😀 '.repeat(9))+i,resource_id:`id-${i}`,bounds:{left:1,top:2,right:3,bottom:4}}));

test('default full accessibility dump stays within text bound and retains snapshot and screenshot',()=>{
  const image={data:'dGVzdA==',mimeType:'image/png'};
  const returned=toolResult({snapshot_id:'current-snapshot',captured_at_ms:1234,package_name:'com.google.android.youtube',nodes:crowdedNodes(),nodes_truncated:false,images:[image]});
  assert.equal(returned.isError,false);
  assert.ok(Buffer.byteLength(returned.content[0].text,'utf8')<=32000);
  assert.deepEqual(JSON.parse(returned.content[0].text),returned.details);
  assert.equal(returned.details.snapshot_id,'current-snapshot');
  assert.equal(returned.details.package_name,'com.google.android.youtube');
  assert.equal(returned.details.nodes_truncated,true);
  assert.ok(returned.details.nodes.length>0);
  assert.equal(returned.details.nodes.length+returned.details.nodes_omitted,150);
  assert.deepEqual(returned.content[1],{type:'image',...image});
});

test('nested batch trimming never discards earlier side effects or their receipts',()=>{
  const receipt={operation_id:'op-123',status:'verified',receipt:{delivered:true}};
  const returned=toolResult({status:'completed',results:[
    {index:0,name:'phone_act',result:receipt},
    {index:1,name:'phone_observe',result:{snapshot_id:'first',nodes:crowdedNodes(),nodes_truncated:false}},
    {index:2,name:'phone_observe',result:{snapshot_id:'second',nodes:crowdedNodes(),nodes_truncated:false}},
  ]});
  assert.equal(returned.isError,false);
  assert.ok(Buffer.byteLength(returned.content[0].text,'utf8')<=32000);
  assert.deepEqual(returned.details.results[0].result,receipt);
  assert.equal(returned.details.results[1].result.snapshot_id,'first');
  assert.equal(returned.details.results[2].result.snapshot_id,'second');
  assert.equal(returned.details.results[1].result.nodes_truncated,true);
  assert.equal(returned.details.results[2].result.nodes_truncated,true);
});

test('non-accessibility oversized response stays an error rather than a false success',()=>{
  const result=toolResult({status:'verified',unbounded_extra:'x'.repeat(50000)});
  assert.equal(result.isError,true);
  assert.equal(result.details.code,'OUTPUT_LIMIT');
});

test('disconnected backend relinquishes lease so a subsequent observe can reacquire',async()=>{
  let reservations=0,releases=0,calls=0,drops=0;
  const host={ensureDisplay:async()=>{reservations++;return {status:'acquired'};},releaseScreen:()=>{releases++;},dropPriorImages:()=>{drops++;},call:()=>{
    calls++;
    return calls===1?{isError:true,code:'NOT_READY',message:'Shizuku backend disconnected'}:{snapshot_id:'new',nodes:[]};
  }};
  const observe=nativeTools(host,'test-owner').find((tool:any)=>tool.name==='phone_observe');
  assert.ok(observe);
  const first=await observe.execute('first',{});
  assert.equal(first.isError,true);
  assert.equal(releases,1);
  const second=await observe.execute('second',{});
  assert.equal(second.isError,false);
  assert.equal(second.details.snapshot_id,'new');
  assert.equal(reservations,2);
  assert.equal(drops,2);
});

test('transient pending-window reads retain the display lease for the next observation',async()=>{
  let calls=0,releases=0;
  const host={ensureDisplay:async()=>({status:'acquired'}),releaseScreen:()=>{releases++;},dropPriorImages:()=>{},call:()=>{
    calls++;
    return calls===1?{isError:true,code:'NOT_READY',message:'Window launch still pending; observe again without replacing the display lease'}:{snapshot_id:'ready-screen',nodes:[]};
  }};
  const observe=nativeTools(host,'test-owner').find((tool:any)=>tool.name==='phone_observe')!;
  assert.equal((await observe.execute('first',{})).details.code,'NOT_READY');
  assert.equal(releases,0,'an app launch delay must not destroy its display');
  assert.equal((await observe.execute('second',{})).details.snapshot_id,'ready-screen');
  assert.equal(releases,0);
});

test('batch nested disconnected read releases lease, preserves completed mutation, and does not replay it',async()=>{
  const receipt={operation_id:'accepted-once',status:'verified'};
  const calls:any[]=[];let releases=0;
  const host={ensureDisplay:async()=>({status:'acquired'}),releaseScreen:()=>{releases++;},dropPriorImages:()=>{},call:(_method:string,payload:any)=>{
    calls.push(payload.name);
    if(payload.name==='phone_batch')return {isError:true,status:'partial',stopped_at:1,retry_safe:false,results:[
      {index:0,name:'phone_act',result:receipt},
      {index:1,name:'phone_observe',result:{isError:true,code:'NOT_READY',message:'Shizuku backend disconnected'}},
    ]};
    return {snapshot_id:'reacquired',nodes:[]};
  }};
  const tools=nativeTools(host,'batch-owner');
  const batch=tools.find((x:any)=>x.name==='phone_batch')!;
  const result=await batch.execute('batch',{calls:[{name:'phone_act',args:{}},{name:'phone_observe',args:{}}]});
  assert.equal(result.isError,true);
  assert.deepEqual(result.details.results[0].result,receipt);
  assert.equal(result.details.stopped_at,1);
  assert.equal(releases,1,'nested read failure releases only the invalid lease');
  const next=await tools.find((x:any)=>x.name==='phone_observe')!.execute('read-only-retry',{});
  assert.equal(next.details.snapshot_id,'reacquired');
  assert.deepEqual(calls,['phone_batch','phone_observe'],'already completed mutation is not replayed');
});

test('batch with two oversized non-node reads keeps completed mutation receipts',()=>{
  const receipt={operation_id:'dont-repeat-this',status:'verified',receipt:{sent:true}};
  const result=toolResult({status:'completed',results:[
    {index:0,name:'phone_act',result:receipt},
    {index:1,name:'phone_notifications',result:{notifications:[{text:'a'.repeat(20000)}]}},
    {index:2,name:'phone_calendar_events',result:{events:[{text:'b'.repeat(20000)}]}},
  ]});
  assert.ok(Buffer.byteLength(result.content[0].text,'utf8')<=32000);
  assert.deepEqual(result.details.results[0].result,receipt);
  assert.equal(result.details.output_truncated,true);
  assert.equal(result.details.retry_safe,false);
  assert.equal(result.details.truncated_read_indices.length,1);
  assert.equal(result.isError,true);
  assert.equal(result.details.results[result.details.truncated_read_indices[0]].result.code,'OUTPUT_LIMIT');
  assert.notEqual(result.details.code,'OUTPUT_LIMIT','batch receipts must not be lost');
});

test('barely oversized accessibility response keeps nearly all selectors',()=>{
  const nodes=Array.from({length:150},(_,index)=>({index,text:'a'.repeat(195)}));
  const result=toolResult({snapshot_id:'retain-most',nodes,nodes_truncated:false});
  assert.equal(result.isError,false);
  assert.ok(result.details.nodes.length>110,'small overflow must not drop half the tree');
  assert.ok(result.details.nodes.length<150);
  assert.equal(result.details.nodes.length+result.details.nodes_omitted,150);
});
