import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {Agent} from '@earendil-works/pi-agent-core';
import {PocketRuntime} from '../src/runtime.js';
import {emptyAutonomy} from '../src/autonomy.js';
import {Type} from 'typebox';
import {DesktopCdp,desktopEndpoint,desktopModels,desktopPrompt} from '../src/chatgpt-desktop.js';
import {DesktopToolRouter} from '../src/desktop-connector.js';
import {makeStream,modelFor} from '../src/provider.js';
import {discoverProviderModels} from '../src/model-catalog.js';

const config={mode:'chatgpt-desktop' as const,endpoint:'http://100.64.0.2:19238',model:'gpt-6-thinking',reasoningEffort:'extended' as const};

test('desktop accepts only tailnet or loopback CDP origins and needs no API key',{timeout:20000},()=>{
  for(const endpoint of ['http://100.64.0.1:19238','https://100.127.255.254:19238','http://127.0.0.1:19238'])assert.equal(desktopEndpoint(endpoint),endpoint);
  for(const endpoint of ['http://100.63.0.1:19238','http://100.128.0.1:19238','http://example.com','http://100.64.0.2/path','http://user@100.64.0.2','file:///etc/passwd','http://100.64.0.2?key=secret'])assert.throws(()=>desktopEndpoint(endpoint));
  const model=modelFor(config,{});assert.equal(model.provider,'chatgpt-desktop');assert.deepEqual(model.input,['text','image']);
  assert.throws(()=>modelFor({...config,endpoint:undefined},{}),/Tailscale/);
});

test('native tool prompts require an active MCP session and preserve image/system context',()=>{
  const tools=[{name:'phone_capabilities',description:'Read capabilities',parameters:Type.Object({})}];
  assert.throws(()=>desktopPrompt({messages:[],tools}),/Enable ChatGPT MCP/);
  const prompt=desktopPrompt({systemPrompt:'Private instructions',tools,messages:[{role:'user',content:[{type:'image',data:'eA==',mimeType:'image/png'},{type:'text',text:'Read the code.'}],timestamp:1}]},'test-session');
  assert.ok(prompt.text.includes('exact pocket_session is test-session'));assert.ok(prompt.text.includes('Use real MCP tool calls'));assert.ok(!prompt.text.includes('"toolCalls":'));
  const normalized=desktopPrompt({messages:[{role:'system',content:'Resolved policy',toolsAdded:tools,timestamp:0},{role:'system',content:'New policy section',toolsRemoved:[{name:'phone_capabilities'}],timestamp:1}]});
  assert.deepEqual(normalized.tools,[]);assert.ok(normalized.text.includes('Resolved policy'));assert.ok(normalized.text.includes('New policy section'));
  assert.equal(prompt.images.length,1);assert.ok(prompt.text.includes('Private instructions'));assert.ok(prompt.text.includes('[Attached image 1]'));assert.ok(!prompt.text.includes('eA=='));
});

function fixture(t:any,answer:(request:any,index:number)=>any,dropAtStart=false,nativeError=false,pauseUntilRebind=false,options:any={}) {
  const started=Promise.withResolvers<void>(),modelsStarted=Promise.withResolvers<void>();const closeWaiters:Array<()=>void>=[];
  const previous=globalThis.WebSocket;
  const sockets=new Set<FakeSocket>();const requests:any[]=[];let stops=0,connections=0;let deferredFinish:(()=>void)|undefined;
  const native={
    get:async()=>options.get?options.get():({}),list:async()=>({}),
    models:async()=>{modelsStarted.resolve();if(options.modelGate)await options.modelGate;return {models:options.models??[{slug:'gpt-6-thinking',title:'Thinking',thinking_efforts:['standard','extended']},{slug:'gpt-disabled',disabled:true}]};},
    stopCompletion:async()=>{stops++;},cancelStream:()=>{stops++;},
    startCompletionStream:(args:any)=>{
      requests.push(args.request);started.resolve();const response=answer(args.request,requests.length);
      if(dropAtStart&&requests.length===1)for(const socket of [...sockets])socket.close();
      if(response)queueMicrotask(async()=>{
        const resolved=await response;
        if(nativeError){args.onError({});return;}
        const text=resolved.text,conversation_id='fixture-conversation-'+requests.length;
        if(resolved.approval){args.onUpdate({conversation_id,message:resolved.approval});args.onComplete({conversation_id});return;}
        for(const [index,block] of (options.fragments??[text]).entries())for(const length of [Math.floor(block.length/2),block.length])args.onUpdate({conversation_id,message:{id:'fixture-answer-'+index,author:{role:'assistant'},channel:'final',recipient:'all',content:{content_type:'text',parts:[block.slice(0,length)]},status:'finished_successfully',end_turn:length===block.length}});
        if(pauseUntilRebind)deferredFinish=()=>args.onComplete({conversation_id});else args.onComplete({conversation_id});
      });
      return {streamRequestId:requests.length};
    },
  };
  const window:any={};window[Symbol.for('terminal-mcp.chat-runtime.client.v5')]=native;
  const context=vm.createContext({window,document:{querySelectorAll:()=>[]},AbortController,Map,Set,WeakSet,Symbol,Promise,Intl,Date,setTimeout,clearTimeout,URL,console,crypto:globalThis.crypto});
  class FakeSocket extends EventTarget {
    static OPEN=1;readyState=0;
    constructor(readonly url:any){super();connections++;sockets.add(this);queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'));});}
    send(raw:string){const msg=JSON.parse(raw);void(async()=>{
      let result:any={};
      if(msg.params?.expression==='__never_resolve')return;
      try{
        if(msg.method==='Runtime.addBinding')window[msg.params.name]=(payload:string)=>{for(const socket of sockets)if(socket.readyState===1)socket.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({method:'Runtime.bindingCalled',params:{name:msg.params.name,payload}})}));};
        else if(msg.method==='Runtime.evaluate')result={result:{value:await vm.runInContext(msg.params.expression,context)}};
        this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({id:msg.id,result})}));
        if(pauseUntilRebind&&msg.params?.expression?.includes('s.binding='))setImmediate(()=>deferredFinish?.());
      }catch(error){this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({id:msg.id,result:{exceptionDetails:{text:String(error)}}})}));}
    })();}
    close(){if(this.readyState===3)return;this.readyState=3;sockets.delete(this);if(!sockets.size)closeWaiters.splice(0).forEach(resolve=>resolve());queueMicrotask(()=>this.dispatchEvent(new Event('close')));}
  }
  globalThis.WebSocket=FakeSocket as any;
  const fetcher:typeof fetch=async(input,init)=>{assert.equal(String(input),config.endpoint+'/json/list');assert.equal(init?.redirect,'error');assert.equal(new Headers(init?.headers).get('authorization'),null);return Response.json([{type:'page',url:'app://-/index.html',webSocketDebuggerUrl:'ws://127.0.0.1:19238/devtools/page/ABC'}]);};
  t.after(()=>{for(const socket of [...sockets])socket.close();globalThis.WebSocket=previous;});
  return {requests,fetcher,sockets,started:started.promise,modelsStarted:modelsStarted.promise,whenClosed:()=>sockets.size?new Promise<void>(resolve=>closeWaiters.push(resolve)):Promise.resolve(),stops:()=>stops,connections:()=>connections};
}

test('desktop discovery uses the native catalog with standard and extended reasoning',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'OK',toolCalls:[]}));
  const models=await discoverProviderModels(config,{},f.fetcher);
  assert.equal(models.length,1);assert.equal(models[0].id,'gpt-6-thinking');assert.deepEqual(models[0].supportedReasoningLevels,['standard','extended']);
});

test('native MCP executes a worker tool once inside the same ChatGPT inference', {timeout:20000},async t=>{
  let executed=0,closed=false;
  const tool={name:'phone_capabilities',label:'Capabilities',description:'Read capabilities',parameters:Type.Object({}),execute:async()=>{executed++;return {content:[{type:'text',text:'fixture-ready'}],details:{}};}};
  const router=new DesktopToolRouter({tools:()=>[tool],persist:()=>true,record:()=>{}});
  const connector={open:(...args:Parameters<typeof router.open>)=>{const scope=router.open(...args);return {...scope,close:()=>{closed=true;scope.close();}};}};
  const f=fixture(t,async request=>{
    const session=request.messages[0].content.parts.at(-1).match(/exact pocket_session is ([a-f0-9-]+)/)[1];
    const input={pocket_session:session,pocket_request:'call-1'};
    const result=await router.call('chatgpt-profile','phone_capabilities',input);
    await router.call('chatgpt-profile','phone_capabilities',input);
    return {text:'Verified '+result.content[0].text};
  });
  const model=modelFor(config,{}),source=makeStream(config,{},'worker',undefined,undefined,undefined,connector);
  const agent=new Agent({streamFn:(m,c,o)=>source(m,c,{...o,fetch:f.fetcher,signal:t.signal}),initialState:{model,tools:[tool]}});
  await agent.prompt('Read capabilities and report the actual result.');
  assert.equal(executed,1);assert.equal(f.requests.length,1);assert.equal(f.requests[0].thinking_effort,'extended');
  assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');assert.equal((agent.state.messages.at(-1) as any).content[0].text,'Verified fixture-ready');
  await f.whenClosed();assert.equal(closed,true);
});

test('assistant JSON text cannot execute tools and a missing MCP session fails before submission',{timeout:20000},async t=>{
  const text='{"text":"Bad call","toolCalls":[{"name":"not_granted","arguments":{}}]}';
  const f=fixture(t,()=>({text}));
  const source=makeStream(config,{},'worker');
  const result=await(await source(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'stop');assert.equal((result.content[0] as any).text,text);assert.equal(result.content.some(p=>p.type==='toolCall'),false);
  const blocked=await(await source(modelFor(config,{}),{messages:[],tools:[{name:'allowed',description:'Granted',parameters:Type.Object({})}]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(blocked.stopReason,'error');assert.equal(f.requests.length,1);
});

test('abort stops only its owned desktop request and releases the connection',{timeout:20000},async t=>{
  const f=fixture(t,()=>null),controller=new AbortController();
  const source=makeStream(config,{},'worker');const stream=await source(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:AbortSignal.any([controller.signal,t.signal])});
  await f.started;controller.abort();
  const result=await stream.result();assert.equal(result.stopReason,'aborted');await f.whenClosed();
  assert.equal(f.stops(),1);assert.equal(f.sockets.size,0);
});


test('a dropped native submission acknowledgement is recovered without resubmission',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'Recovered once.',toolCalls:[]}),true);
  const stream=await makeStream(config,{},'recover')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal});
  const result=await stream.result();assert.equal(result.stopReason,'stop',result.errorMessage);assert.equal(f.requests.length,1);
  assert.equal(result.content.find(part=>part.type==='text')?.text,'Recovered once.');
});

test('main and worker streams retain distinct native request state',{timeout:20000},async t=>{
  const f=fixture(t,request=>({text:request.messages[0].content.parts[0].match(/RAW_TOKEN=([A-Z]+)/)?.[1]||'missing',toolCalls:[]}));
  const run=async(owner:string,token:string)=>{
    const stream=await makeStream(config,{},owner)(modelFor(config,{}),{messages:[{role:'user',content:[{type:'text',text:'RAW_TOKEN='+token}],timestamp:1}]},{fetch:f.fetcher,signal:t.signal});
    return stream.result();
  };
  const [main,worker]=await Promise.all([run('main','ALPHA'),run('worker','BRAVO')]);
  assert.equal(main.stopReason,'stop',main.errorMessage);assert.equal(worker.stopReason,'stop',worker.errorMessage);
  assert.equal(main.content.find(part=>part.type==='text')?.text,'ALPHA');assert.equal(worker.content.find(part=>part.type==='text')?.text,'BRAVO');assert.equal(f.requests.length,2);
});


test('recovery terminates a confirmed native failure instead of retrying it',{timeout:10000},async t=>{
  const f=fixture(t,()=>({text:'',toolCalls:[]}),true,true);
  const stream=await makeStream(config,{},'failed-worker')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal});
  const result=await stream.result();assert.equal(result.stopReason,'error');assert.match(result.errorMessage||'',/inference failed/);assert.equal(f.requests.length,1);
});


test('late close events from the old connection cannot retire a recovered active stream',{timeout:10000},async t=>{
  const f=fixture(t,()=>({text:'Resumed on the replacement socket.',toolCalls:[]}),true,false,true);
  const stream=await makeStream(config,{},'active-recovery')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal});
  const result=await stream.result();assert.equal(result.stopReason,'stop',result.errorMessage);assert.equal(f.requests.length,1);assert.equal(f.connections(),2);
});


test('a CDP command timeout marks the transport unavailable for request reconciliation',{timeout:10000},async t=>{
  const f=fixture(t,()=>({text:'unused',toolCalls:[]}));const events:string[]=[];
  const cdp=await DesktopCdp.connect(config.endpoint,method=>events.push(method),t.signal,f.fetcher);
  try{await assert.rejects(cdp.call('Runtime.evaluate',{expression:'__never_resolve'},2),/timed out/);assert.ok(events.includes('timeout'));}finally{cdp.close();}
});


test('native final continuation messages are merged as ordinary text',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'unused'}),false,false,false,{fragments:['Ready.','Finished.']});
  const result=await(await makeStream(config,{},'continuation')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'stop',result.errorMessage);assert.ok((result.content[0] as any).text.includes('Ready.'));assert.ok((result.content[0] as any).text.includes('Finished.'));
});

test('abort reconnects only to cancel the owned request after a dropped socket',{timeout:20000},async t=>{
  const f=fixture(t,()=>null,true),controller=new AbortController();
  const stream=await makeStream(config,{},'cancel-disconnected')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:AbortSignal.any([controller.signal,t.signal])});
  await f.started;controller.abort();const result=await stream.result();await f.whenClosed();
  assert.equal(result.stopReason,'aborted');assert.equal(f.requests.length,1);assert.equal(f.stops(),1);assert.ok(f.connections()>=2);assert.equal(f.sockets.size,0);
});

test('desktop discovery rejects an empty catalog',{timeout:20000},async t=>{
  const f=fixture(t,()=>null,false,false,false,{models:[]});
  await assert.rejects(desktopModels(config.endpoint,f.fetcher,t.signal),/no available models/);
});

test('desktop discovery respects cancellation during native model RPC',{timeout:20000},async t=>{
  const g=fixture(t,()=>null,false,false,false,{modelGate:new Promise(()=>{})}),controller=new AbortController();
  const pending=desktopModels(config.endpoint,g.fetcher,controller.signal);await g.modelsStarted;controller.abort();
  await assert.rejects(pending,/cancelled|disconnected/i);await g.whenClosed();assert.equal(g.sockets.size,0);
});

test('native advertised API-style thinking levels are preserved and serialized',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'OK',toolCalls:[]}),false,false,false,{models:[{slug:'gpt-6-thinking',thinking_efforts:['low','high']}]});
  assert.deepEqual((await desktopModels(config.endpoint,f.fetcher,t.signal))[0].supportedReasoningLevels,['low','high']);
  const result=await(await makeStream({...config,reasoningEffort:'high'},{},'main','high',['low','high'])(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'stop',result.errorMessage);assert.equal(f.requests[0].thinking_effort,'high');
});


test('unverified API-style default efforts are omitted from native requests',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'OK',toolCalls:[]}));
  const result=await(await makeStream({...config,reasoningEffort:'low'},{},'main')(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'stop',result.errorMessage);assert.equal(f.requests[0].thinking_effort,undefined);
});

test('native MCP workers execute under their own owner and deliver durable results to main',{timeout:20000},async t=>{
  let runtime:PocketRuntime,saved:any={},calls:any[]=[];const completed=Promise.withResolvers<void>();
  const f=fixture(t,async(request,index)=>{
    const prompt=request.messages[0].content.parts.at(-1),session=prompt.match(/exact pocket_session is ([a-f0-9-]+)/)[1];
    if(index===1){
      const result=await runtime.connector.call('authorized-phone','subagent',{pocket_session:session,pocket_request:'spawn-one',action:'spawn',spawns:[{agent:'phone-operator',label:'Read capabilities',prompt:'Read the actual phone capabilities receipt.'}]});
      assert.ok(JSON.stringify(result).includes('conversationId'));return {text:'Worker dispatched.'};
    }
    if(index===2){
      const result=await runtime.connector.call('authorized-phone','phone_capabilities',{pocket_session:session,pocket_request:'read-one'});
      assert.ok(JSON.stringify(result).includes('actual-worker-receipt'));return {text:'Verified actual-worker-receipt'};
    }
    assert.ok(prompt.includes('actual-worker-receipt'));return {text:'Worker reported actual-worker-receipt'};
  });
  const previousFetch=globalThis.fetch;globalThis.fetch=f.fetcher;t.after(()=>{globalThis.fetch=previousFetch;});
  runtime=new PocketRuntime({call(method,json){const args=JSON.parse(json);let result:any={};
    if(method==='credentials.get')result={authMode:'chatgpt-desktop',endpoint:config.endpoint,model:config.model};
    if(method==='connector.status')result={ready:true};
    if(method==='store.load')result=saved;if(method==='store.save'){saved=structuredClone(args.state);result={saved:true};}
    if(method==='autonomy.load')result=emptyAutonomy();if(method==='autonomy.status')result={enabled:false};if(method==='voice.preferences')result={mode:'text'};
    if(method==='tool'){calls.push(args);assert.equal(args.name,'phone_capabilities');assert.notEqual(args.owner,'main');result={receipt:'actual-worker-receipt'};}
    return JSON.stringify({ok:true,result});
  },emit(json){const event=JSON.parse(json);if(event.type==='state'&&!event.state.active&&event.state.tasks?.some((task:any)=>task.status==='completed')&&event.state.messages.some((message:any)=>message.text?.includes('Worker reported actual-worker-receipt')))completed.resolve();}});
  t.after(()=>{runtime.dispatch(JSON.stringify({action:'stop_all'}));runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));});
  assert.equal(JSON.parse(runtime.dispatch(JSON.stringify({action:'send',text:'Delegate the read to a phone worker.'}))).ok,true);
  await Promise.race([completed.promise,new Promise((_,reject)=>t.signal.addEventListener('abort',()=>reject(Error('Worker delivery timed out')),{once:true}))]);
  assert.equal(calls.length,1);assert.equal(saved.delivered.length,1);
  const worker=saved.workerMessages[calls[0].owner];assert.ok(worker.some((message:any)=>message.role==='toolResult'&&message.toolName==='phone_capabilities'));
  assert.ok(saved.mainMessages.some((message:any)=>message.role==='toolResult'&&message.toolName==='subagent'));
});

test('native one-use approval continues the same conversation without resending the user prompt',{timeout:20000},async t=>{
  let pending:any;const router=new DesktopToolRouter({tools:()=>[{name:'read_fixture',description:'Read fixture',parameters:Type.Object({}),execute:async()=>({content:[{type:'text',text:'verified-native-call'}]})}],persist:()=>true,record:()=>{}});
  const f=fixture(t,async(request,index)=>{
    if(index===1){const session=request.messages[0].content.parts[0].match(/exact pocket_session is ([a-f0-9-]+)/)[1];
      pending={id:'approval-head',author:{role:'tool'},metadata:{jit_plugin_data:{from_server:{type:'confirm_action',body:{connector_name:'PocketAgent',connector_id:'asdk_app_test',link_id:'link_test',codex_mcp_elicitation:{kind:'mcpToolCall',status:'pending'},params:{path:'/asdk_app_test/link_test/read_fixture',args:{pocket_session:session,pocket_request:'read-one'}},actions:[{allow:{target_message_id:'approval-head'}}]}}}}};return {approval:pending};
    }
    assert.equal(request.parent_message_id,'approval-head');assert.equal(request.conversation_id,'fixture-conversation-1');assert.equal(request.messages[0].author.role,'tool');
    assert.deepEqual(JSON.parse(JSON.stringify(request.messages[0].metadata.jit_plugin_data.from_client)),{target_message_id:'approval-head',type:'allow',remember_answer:false});
    const result=await router.call('authorized','read_fixture',pending.metadata.jit_plugin_data.from_server.body.params.args);return {text:result.content[0].text};
  },false,false,false,{get:()=>({current_node:'head',mapping:{head:{message:pending,parent:'user'},user:{message:f.requests[0].messages[0],parent:null}}})});
  const source=makeStream(config,{},'main',undefined,undefined,undefined,router);
  const result=await(await source(modelFor(config,{}),{messages:[],tools:[{name:'read_fixture',description:'Read fixture',parameters:Type.Object({})}]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'stop',result.errorMessage);assert.equal((result.content[0] as any).text,'verified-native-call');assert.equal(f.requests.length,2);
});
