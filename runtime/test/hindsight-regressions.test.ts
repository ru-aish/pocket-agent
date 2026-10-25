import test from 'node:test';
import assert from 'node:assert/strict';
import {HindsightMemory} from '../src/hindsight.js';
import {McpToolRegistry} from '../src/mcp.js';
import {PocketRuntime} from '../src/runtime.js';
import {delay} from '../src/scripted.js';

const originalUrl='https://memory.example.test/mcp/old/';
const replacementUrl='https://memory.example.test/mcp/new/';
const server={id:'00000000-0000-4000-8000-000000000001',name:'Hindsight',url:originalUrl};
const advertised=[
  {name:'retain',inputSchema:{type:'object',properties:{content:{type:'string'}}}},
  {name:'recall',inputSchema:{type:'object',properties:{query:{type:'string'}}}},
];

async function until(check:()=>boolean):Promise<void>{
  const deadline=Date.now()+5_000;
  while(!check()){
    if(Date.now()>deadline)throw new Error('Timed out waiting for condition');
    await delay(10);
  }
}

test('a full Hindsight backlog retries on the next turn when the same bank recovers',async()=>{
  let online=false,snapshot:any[]=[];
  const retained:string[]=[];
  const registry=new McpToolRegistry(undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{
      if(name==='retain')retained.push(args.content);
      return online?{content:[{type:'text',text:'ok'}]}:{isError:true,content:[]};
    }},
    tools:advertised,
  }) as any);
  const memory=new HindsightMemory(registry,()=>originalUrl,()=>{
    snapshot=memory.snapshot();return true;
  },text=>text);
  for(let i=0;i<100;i++)memory.capture('event-'+i,'conversation');
  assert.equal(snapshot.length,100);
  await registry.refresh([server]);
  online=true;
  memory.capture('recovery trigger','conversation');
  await until(()=>snapshot.length===0);
  assert.ok(retained.includes('recovery trigger'));
  assert.ok(retained.includes('event-99'));
  assert.ok(!retained.includes('event-0'),'the bounded queue may evict its oldest event');
  await registry.close();
});

test('a full old-bank backlog never blocks new-bank events or redirects private data',async()=>{
  let bank=originalUrl,snapshot:any[]=[];
  const retained:string[]=[];
  const registry=new McpToolRegistry(undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{
      if(name==='retain')retained.push(args.content);
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  const memory=new HindsightMemory(registry,()=>bank,()=>{
    snapshot=memory.snapshot();return true;
  },text=>text);
  for(let i=0;i<100;i++)memory.capture('old-private-'+i,'conversation');
  bank=replacementUrl;
  await registry.refresh([{...server,url:replacementUrl}]);
  memory.capture('new-bank-event','conversation');
  await until(()=>retained.includes('new-bank-event'));
  await until(()=>snapshot.length===99);
  assert.deepEqual(retained,['new-bank-event']);
  assert.ok(snapshot.every(item=>item.target===originalUrl));
  await registry.close();
});

test('in-flight retain cannot remove the wrong event after bounded eviction',async()=>{
  let release:((value:any)=>void)|undefined,snapshot:any[]=[];
  const transmitted:string[]=[];
  const registry=new McpToolRegistry(undefined,async()=>({
    client:{close:async()=>{},callTool:async(name:string,args:any)=>{
      if(name==='retain'){
        transmitted.push(args.content);
        if(!release)return new Promise(resolve=>{release=resolve;});
      }
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  await registry.refresh([server]);
  const memory=new HindsightMemory(registry,()=>originalUrl,()=>{
    snapshot=memory.snapshot();return true;
  },text=>text);
  memory.restore(Array.from({length:100},(_,i)=>({
    id:'restored-'+i,target:originalUrl,content:'old-'+i,context:'conversation',
    timestamp:new Date().toISOString(),
  })));
  const flushing=memory.flush();
  await until(()=>!!release);
  memory.capture('new-while-in-flight','conversation');
  assert.equal(snapshot.length,100);
  release!({content:[{type:'text',text:'ok'}]});
  await flushing;
  await until(()=>snapshot.length===0);
  assert.ok(transmitted.includes('new-while-in-flight'));
  assert.ok(transmitted.includes('old-99'));
  await registry.close();
});

test('full-queue replacement rolls back when its durable checkpoint fails',()=>{
  let fail=false,snapshot:any[]=[];
  const registry=new McpToolRegistry();
  const memory=new HindsightMemory(registry,()=>originalUrl,()=>{
    if(fail)return false;
    snapshot=memory.snapshot();return true;
  },text=>text);
  for(let i=0;i<100;i++)memory.capture('old-'+i,'conversation');
  const before=memory.snapshot();
  fail=true;
  memory.capture('must not enter failed checkpoint','conversation');
  assert.deepEqual(memory.snapshot(),before);
  assert.deepEqual(snapshot,before);
});

test('runtime redacts complete credentials before each Hindsight truncation boundary',async()=>{
  const promptSecret='synthetic-prompt-boundary-token-987654';
  const responseSecret='synthetic-response-boundary-token-123456';
  const configured={...server,authMode:'bearer',bearerToken:promptSecret};
  const inactiveCredential={...server,id:'inactive',enabled:false,headerValue:responseSecret};
  const calls:any[]=[];
  let checkpoint:any={};
  const bridge={
    call(method:string,json:string):string{
      const args=JSON.parse(json);
      let result:any={};
      if(method==='credentials.get')result={authMode:'scripted',mcpServers:[configured,inactiveCredential]};
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
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  const response='r'.repeat(3497)+responseSecret+' after response';
  runtime.main.continue=async()=>{
    runtime.main.agent.state.messages.push({role:'assistant',
      content:[{type:'text',text:response}],stopReason:'stop',timestamp:Date.now()});
  };
  runtime.dispatch(JSON.stringify({action:'send',text:'p'.repeat(4197)+promptSecret+' after request'}));
  await until(()=>calls.some(call=>call.name==='retain'));
  const retained=calls.find(call=>call.name==='retain').args.content as string;
  assert.equal((retained.match(/\[re/g)??[]).length,2,
    'the redaction markers may be cut at the length boundary, never the secret itself');
  for(const secret of [promptSecret,responseSecret]){
    assert.ok(!retained.includes(secret));
    assert.ok(!retained.includes(secret.slice(0,12)),'no token prefix may survive truncation');
  }
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});

test('a failed checkpoint after Stop does not replay the canceled request',async()=>{
  let checkpoint:any={},releaseRecall:((value:any)=>void)|undefined,failNextSave=0;
  const bridge={
    call(method:string,json:string):string{
      const args=JSON.parse(json);
      if(method==='store.save'&&failNextSave-->0)throw new Error('synthetic checkpoint failure');
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
    client:{close:async()=>{},callTool:async(name:string)=>{
      if(name==='recall'&&!releaseRecall)return new Promise(resolve=>{releaseRecall=resolve;});
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  let infer=0;
  runtime.main.continue=async()=>{infer++;};
  runtime.dispatch(JSON.stringify({action:'send',text:'canceled command'}));
  await until(()=>!!releaseRecall);
  failNextSave=1;
  const refused=JSON.parse(runtime.dispatch(JSON.stringify({action:'stop_main'})));
  assert.equal(refused.ok,false,'Stop cannot acknowledge an unpersisted cancellation');
  assert.equal((runtime as any).inbox.length,1,'failed Stop retains the active request');
  const accepted=JSON.parse(runtime.dispatch(JSON.stringify({action:'stop_main'})));
  assert.equal(accepted.ok,true);
  const durableAtStop=structuredClone(checkpoint);
  assert.equal(durableAtStop.inbox?.length,0,'the Stop receipt must already exclude the request');
  assert.ok(!JSON.stringify(durableAtStop.mainMessages).includes('canceled command'));
  let replayedInference=0;
  const reopened=new PocketRuntime({
    call(method:string,json:string):string {
      if(method==='store.load')return JSON.stringify({ok:true,result:durableAtStop});
      if(method==='store.save')return JSON.stringify({ok:true,result:{}});
      return bridge.call(method,json);
    },
    emit(_json:string):void{},
  },undefined,async()=>({client:{close:async()=>{},callTool:async()=>({content:[]})},tools:advertised}) as any);
  reopened.main.continue=async()=>{replayedInference++;};
  await new Promise(resolve=>setTimeout(resolve,60));
  assert.equal(replayedInference,0,'the saved Stop checkpoint must never replay after restart');
  reopened.dispatch(JSON.stringify({action:'prepare_shutdown'}));
  releaseRecall!({content:[{type:'text',text:'old recall'}]});
  await until(()=>!runtime.state().busy);
  assert.equal(infer,0);
  assert.equal((runtime as any).inbox.length,0);
  await until(()=>failNextSave<0&&checkpoint.inbox?.length===0);
  assert.ok(!JSON.stringify(checkpoint.mainMessages).includes('canceled command'));
  runtime.dispatch(JSON.stringify({action:'send',text:'new command'}));
  await until(()=>infer===1&&!runtime.state().busy);
  assert.ok(!JSON.stringify(runtime.main.messages).includes('canceled command'));
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});

test('worker completion during main recall is delivered after the transaction closes',async()=>{
  let checkpoint:any={},releaseMainRecall:((value:any)=>void)|undefined;
  const bridge={
    call(method:string,json:string):string{
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
      if(name==='recall'&&args.query==='hold the main turn'&&!releaseMainRecall)
        return new Promise(resolve=>{releaseMainRecall=resolve;});
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  const subagent=runtime.main.agent.state.tools.find((tool:any)=>tool.name==='subagent');
  runtime.main.continue=async()=>{};
  runtime.dispatch(JSON.stringify({action:'send',text:'hold the main turn'}));
  await until(()=>!!releaseMainRecall);
  await subagent.execute('test-completion-recall',{action:'spawn',
    spawns:[{agent:'researcher',label:'Fast worker',prompt:'Summarize task status'}]});
  await until(()=>runtime.state().tasks[0]?.status==='completed');
  assert.equal(runtime.state().mainTurns,1,'the main transaction is still waiting on recall');
  releaseMainRecall!({content:[{type:'text',text:'ok'}]});
  await until(()=>runtime.state().mainTurns===2&&!runtime.state().busy);
  assert.ok(checkpoint.delivered?.length>0,
    'the completion receipt must be durably collected after the recall transaction');
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});

test('canceling a worker during Hindsight recall never starts that worker inference',async()=>{
  let checkpoint:any={},releaseRecall:((value:any)=>void)|undefined;
  const bridge={
    call(method:string,json:string):string{
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
    client:{close:async()=>{},callTool:async(name:string)=>{
      if(name==='recall'&&!releaseRecall)return new Promise(resolve=>{releaseRecall=resolve;});
      return {content:[{type:'text',text:'ok'}]};
    }},
    tools:advertised,
  }) as any);
  await until(()=>runtime.debugToolList().some(tool=>tool.name.endsWith('_recall')));
  const subagent=runtime.main.agent.state.tools.find((tool:any)=>tool.name==='subagent');
  assert.ok(subagent);
  await subagent.execute('test-worker-recall',{action:'spawn',
    spawns:[{agent:'researcher',label:'Cancellable worker',prompt:'Perform only this read'}]});
  await until(()=>!!releaseRecall&&runtime.state().tasks.length===1);
  const conversationId=runtime.state().tasks[0].conversationId;
  const session=(runtime as any).sessions.get(conversationId);
  assert.ok(session);
  let inferenceCalls=0;
  session.prompt=async()=>{inferenceCalls++;};
  runtime.dispatch(JSON.stringify({action:'cancel_worker',conversationId}));
  releaseRecall!({content:[{type:'text',text:'old memory'}]});
  await until(()=>runtime.state().tasks[0]?.status==='cancelled');
  assert.equal(inferenceCalls,0);
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
});
