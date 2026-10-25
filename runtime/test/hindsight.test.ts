import test from 'node:test';
import assert from 'node:assert/strict';
import {HindsightMemory} from '../src/hindsight.js';
import {McpToolRegistry} from '../src/mcp.js';
import {PocketRuntime} from '../src/runtime.js';
import {delay} from '../src/scripted.js';

const url='https://memory.example.test/mcp/pocket-agent/';
const server={id:'00000000-0000-4000-8000-000000000001',name:'Hindsight',url};
const advertised=[
  {name:'retain',inputSchema:{type:'object',properties:{content:{type:'string'}}}},
  {name:'recall',inputSchema:{type:'object',properties:{query:{type:'string'}}}},
];

async function until(check:()=>boolean):Promise<void>{
  const deadline=Date.now()+4_000;
  while(!check()) {
    if(Date.now()>deadline)throw Error('Timed out waiting for test condition');
    await delay(10);
  }
}

test('Hindsight stores locally before sending and retries a failed retain across restart',async()=>{
  let online=false,saved:any[]=[];const calls:any[]=[];
  const connector=async()=>({client:{close:async()=>{},callTool:async(name:string,args:any)=>{
    calls.push({name,args});return online?{content:[{type:'text',text:'ok'}]}:{isError:true,content:[]};
  }},tools:advertised}) as any;
  const registry=new McpToolRegistry(undefined,connector);
  const memory=new HindsightMemory(registry,()=>url,()=>{saved=memory.snapshot();return true;},s=>s.replaceAll('secret-token','[redacted]'));
  memory.capture('Worker verified result; secret-token','worker_outcome');
  assert.equal(saved.length,1);
  assert.match(saved[0].content,/\[redacted\]/);
  await registry.refresh([server]);
  await memory.flush();
  assert.equal(saved.length,1,'failed retain remains durable');
  await registry.close();

  online=true;
  const reopenedRegistry=new McpToolRegistry(undefined,connector);
  let reopenedSnapshot:any[]=saved;
  const reopened=new HindsightMemory(reopenedRegistry,()=>url,()=>{reopenedSnapshot=reopened.snapshot();return true;},s=>s);
  reopened.restore(saved);
  await reopenedRegistry.refresh([server]);
  await reopened.flush();
  assert.deepEqual(reopenedSnapshot,[]);
  assert.ok(calls.some(call=>call.name==='retain'&&call.args.metadata.event_id===saved[0].id));
  await reopenedRegistry.close();
});

test('Hindsight never redirects queued transcript data when the bank URL changes',async()=>{
  let target=url;let snapshot:any[]=[];const calls:any[]=[];
  const registry=new McpToolRegistry(undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{calls.push({name,args});return {content:[{type:'text',text:'ok'}]};}},
    tools:advertised,
  }) as any);
  const memory=new HindsightMemory(registry,()=>target,()=>{snapshot=memory.snapshot();return true;},s=>s);
  memory.capture('private context','conversation');
  target='https://memory.example.test/mcp/other-bank/';
  await registry.refresh([{...server,url:target}]);
  await memory.flush();
  assert.equal(snapshot.length,1);
  assert.equal(calls.length,0);
  memory.capture('new bank context','conversation');
  await until(()=>calls.length===1&&snapshot.length===1);
  assert.equal(snapshot.length,1,'the old-bank event stays queued');
  assert.equal(snapshot[0].content,'private context');
  assert.equal(calls.length,1,'a new bank can still accept its own event');
  assert.equal(calls[0].args.content,'new bank context');
  await registry.close();
});

test('MCP Hindsight recall is bounded and treated as untrusted information',async()=>{
  const registry=new McpToolRegistry(undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string)=>({content:[{type:'text',text:name==='recall'?'User once said they prefer JavaScript.':'ok'}]})},
    tools:advertised,
  }) as any);
  await registry.refresh([server]);
  const memory=new HindsightMemory(registry,()=>url,()=>true,s=>s);
  const recalled=await memory.recall('programming preference');
  assert.match(recalled,/prefer JavaScript/);
  assert.match(recalled,/untrusted retrieved data/);
  await registry.close();
});

test('Pocket runtime recalls and retains through existing MCP without changing local memory tools',async()=>{
  const calls:any[]=[];let checkpoint:any={};
  const bridge={
    call(method:string,json:string):string {
      const args=JSON.parse(json);
      let result:any={};
      if(method==='credentials.get')result={authMode:'scripted',mcpServers:[server]};
      if(method==='store.load')result=checkpoint;
      if(method==='store.save')checkpoint=structuredClone(args.state);
      if(method==='memory')result={memories:[]};
      if(method==='tool')result={available:true};
      return JSON.stringify({ok:true,result});
    },
    emit(_json:string):void{},
  };
  const runtime=new PocketRuntime(bridge,undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{
      calls.push({name,args});
      return {content:[{type:'text',text:name==='recall'?'A previous troubleshooting case was resolved.':'accepted'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  runtime.dispatch(JSON.stringify({action:'send',text:'Resume the troubleshooting task'}));
  await until(()=>runtime.state().mainTurns===1&&!runtime.state().busy&&calls.some(c=>c.name==='retain'));
  await until(()=>checkpoint.hindsightPending?.length===0);
  assert.ok(calls.some(c=>c.name==='recall'&&c.args.query.includes('troubleshooting task')));
  assert.ok(calls.some(c=>c.name==='retain'&&c.args.content.includes('Resume the troubleshooting task')));
  assert.match(runtime.main.messages[0].content,/A previous troubleshooting case was resolved/);
  assert.ok(runtime.debugToolList().some(tool=>tool.name==='memory_search'));
  assert.ok(Array.isArray(checkpoint.hindsightPending));
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});

test('Stop during remote recall prevents inference and redacts secrets sent to Hindsight',async()=>{
  const secret='synthetic-hindsight-bearer-secret';
  const configured={...server,authMode:'bearer',bearerToken:secret};
  let checkpoint:any={},releaseRecall:((result:any)=>void)|undefined,querySeen='';
  const events:any[]=[];
  const voiceId='9ac6c359-d522-4fa3-a00b-a7efdf0c9571';
  const bridge={
    call(method:string,json:string):string {
      const args=JSON.parse(json);
      let result:any={};
      if(method==='credentials.get')result={authMode:'scripted',mcpServers:[configured]};
      if(method==='store.load')result=checkpoint;
      if(method==='store.save')checkpoint=structuredClone(args.state);
      if(method==='memory')result={memories:[]};
      if(method==='tool')result={available:true};
      return JSON.stringify({ok:true,result});
    },
    emit(json:string):void{events.push(JSON.parse(json));}
  };
  const runtime=new PocketRuntime(bridge,undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{
      if(name==='recall'&&!releaseRecall) {
        querySeen=args.query;
        return new Promise(resolve=>{releaseRecall=resolve;});
      }
      return {content:[{type:'text',text:'accepted'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  let inferenceCalls=0;
  runtime.main.continue=async()=>{inferenceCalls++;};
  runtime.dispatch(JSON.stringify({action:'send',text:'Stop this request: '+secret,voiceSessionId:voiceId}));
  await until(()=>!!releaseRecall);
  assert.equal(runtime.state().busy,true);
  assert.equal(JSON.parse(runtime.dispatch(JSON.stringify({action:'stop_main',voiceSessionId:voiceId}))).ok,true);
  releaseRecall!({content:[{type:'text',text:'Old context'}]});
  await until(()=>!runtime.state().busy);
  assert.equal(inferenceCalls,0,'an abort before inference must not become a new provider request');
  assert.equal(runtime.state().mainInterrupted,true);
  assert.equal(runtime.state().messages.filter(message=>message.role==='assistant').length,0);
  assert.ok(!checkpoint.mainMessages.some((message:any)=>message.role==='user'),
    'stopped instructions must not remain in the model transcript or checkpoint');
  assert.equal(events.filter(event=>event.type==='voice_end'&&event.sessionId===voiceId).length,1,
    'stopping during voice recall must release Android responding state');
  runtime.dispatch(JSON.stringify({action:'send',text:'New harmless request'}));
  await until(()=>inferenceCalls===1&&!runtime.state().busy);
  assert.ok(!JSON.stringify(runtime.main.messages).includes('Stop this request'),
    'later model context must not contain an actionable stopped instruction');
  assert.ok(querySeen.includes('[redacted]'));
  assert.ok(!querySeen.includes(secret));
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});
