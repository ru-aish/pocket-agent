import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {Agent} from '@earendil-works/pi-agent-core';
import {Type} from 'typebox';
import {desktopEndpoint,desktopPrompt,parseDesktopAnswer} from '../src/chatgpt-desktop.js';
import {makeStream,modelFor} from '../src/provider.js';
import {discoverProviderModels} from '../src/model-catalog.js';

const config={mode:'chatgpt-desktop' as const,endpoint:'http://100.64.0.2:19238',model:'gpt-6-thinking',reasoningEffort:'extended' as const};

test('desktop accepts only tailnet or loopback CDP origins and needs no API key',{timeout:20000},()=>{
  for(const endpoint of ['http://100.64.0.1:19238','https://100.127.255.254:19238','http://127.0.0.1:19238'])assert.equal(desktopEndpoint(endpoint),endpoint);
  for(const endpoint of ['http://100.63.0.1:19238','http://100.128.0.1:19238','http://example.com','http://100.64.0.2/path','http://user@100.64.0.2','file:///etc/passwd','http://100.64.0.2?key=secret'])assert.throws(()=>desktopEndpoint(endpoint));
  const model=modelFor(config,{});assert.equal(model.provider,'chatgpt-desktop');assert.deepEqual(model.input,['text','image']);
  assert.throws(()=>modelFor({...config,endpoint:undefined},{}),/Tailscale/);
});

test('desktop tool protocol rejects prose, unknown tools, malformed arguments and excessive calls',{timeout:20000},()=>{
  const tools=[{name:'phone_capabilities',description:'Read capabilities',parameters:Type.Object({})}];
  assert.deepEqual(parseDesktopAnswer('{"text":"Checking","toolCalls":[{"name":"phone_capabilities","arguments":{}}]}',tools).toolCalls[0].arguments,{});
  for(const text of ['Done','```json\n{}\n```','{"text":"done","toolCalls":[{"name":"shell","arguments":{}}]}','{"text":"done","toolCalls":[{"name":"phone_capabilities","arguments":[]}]}',JSON.stringify({text:'',toolCalls:Array(33).fill({name:'phone_capabilities',arguments:{}})})])assert.throws(()=>parseDesktopAnswer(text,tools));
  const prompt=desktopPrompt({systemPrompt:'Private instructions',tools,messages:[{role:'user',content:[{type:'image',data:'eA==',mimeType:'image/png'},{type:'text',text:'Read the code.'}],timestamp:1}]});
  assert.equal(prompt.images.length,1);assert.ok(prompt.text.includes('Private instructions'));assert.ok(prompt.text.includes('[Attached image 1]'));assert.ok(!prompt.text.includes('eA=='));
});

function fixture(t:any,answer:(request:any,index:number)=>{text:string;toolCalls:any[]}|null) {
  const previous=globalThis.WebSocket;
  const sockets=new Set<FakeSocket>();const requests:any[]=[];let stops=0;
  const native={
    get:async()=>({}),list:async()=>({}),
    models:async()=>({models:[{slug:'gpt-6-thinking',title:'Thinking',thinking_efforts:['standard','extended']},{slug:'gpt-disabled',disabled:true}]}),
    stopCompletion:async()=>{stops++;},cancelStream:()=>{},
    startCompletionStream:(args:any)=>{
      requests.push(args.request);const response=answer(args.request,requests.length);
      if(response)queueMicrotask(()=>{
        const text=JSON.stringify(response),conversation_id='fixture-conversation-'+requests.length;
        for(const length of [Math.floor(text.length/2),text.length])args.onUpdate({conversation_id,message:{id:'fixture-answer',author:{role:'assistant'},channel:'final',recipient:'all',content:{content_type:'text',parts:[text.slice(0,length)]},status:'finished_successfully',end_turn:length===text.length}});
        args.onComplete({conversation_id});
      });
      return {cancel:()=>{stops++;}};
    },
  };
  const window:any={};window[Symbol.for('terminal-mcp.chat-runtime.client.v5')]=native;
  const context=vm.createContext({window,document:{querySelectorAll:()=>[]},AbortController,Map,Set,WeakSet,Symbol,Promise,Intl,Date,setTimeout,clearTimeout,URL,console});
  class FakeSocket extends EventTarget {
    static OPEN=1;readyState=0;
    constructor(readonly url:any){super();sockets.add(this);queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'));});}
    send(raw:string){const msg=JSON.parse(raw);void(async()=>{
      let result:any={};
      try{
        if(msg.method==='Runtime.addBinding')window[msg.params.name]=(payload:string)=>{for(const socket of sockets)if(socket.readyState===1)socket.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({method:'Runtime.bindingCalled',params:{name:msg.params.name,payload}})}));};
        else if(msg.method==='Runtime.evaluate')result={result:{value:await vm.runInContext(msg.params.expression,context)}};
        this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({id:msg.id,result})}));
      }catch(error){this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({id:msg.id,result:{exceptionDetails:{text:String(error)}}})}));}
    })();}
    close(){if(this.readyState===3)return;this.readyState=3;sockets.delete(this);queueMicrotask(()=>this.dispatchEvent(new Event('close')));}
  }
  globalThis.WebSocket=FakeSocket as any;
  const fetcher:typeof fetch=async(input,init)=>{assert.equal(String(input),config.endpoint+'/json/list');assert.equal(init?.redirect,'error');assert.equal(new Headers(init?.headers).get('authorization'),null);return Response.json([{type:'page',url:'app://-/index.html',webSocketDebuggerUrl:'ws://127.0.0.1:19238/devtools/page/ABC'}]);};
  t.after(()=>{for(const socket of [...sockets])socket.close();globalThis.WebSocket=previous;});
  return {requests,fetcher,sockets,stops:()=>stops};
}

test('desktop discovery uses the native catalog with standard and extended reasoning',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'OK',toolCalls:[]}));
  const models=await discoverProviderModels(config,{},f.fetcher);
  assert.equal(models.length,1);assert.equal(models[0].id,'gpt-6-thinking');assert.deepEqual(models[0].supportedReasoningLevels,['standard','extended']);
});

test('Pi executes a desktop-requested tool once and supplies its actual result to the next inference',{timeout:20000},async t=>{
  let executed=0;
  const f=fixture(t,(_request,index)=>index===1?{text:'Checking capabilities.',toolCalls:[{name:'phone_capabilities',arguments:{}}]}:{text:'Verified fixture-ready.',toolCalls:[]});
  const model=modelFor(config,{}),source=makeStream(config,{},'main');const events:any[]=[];
  const agent=new Agent({streamFn:(m,c,o)=>source(m,c,{...o,fetch:f.fetcher,signal:t.signal}),initialState:{model,tools:[{name:'phone_capabilities',label:'Capabilities',description:'Read capabilities',parameters:Type.Object({}),execute:async()=>{executed++;return {content:[{type:'text',text:'fixture-ready'}],details:{}};}}]}});
  agent.subscribe(e=>events.push(e));await agent.prompt('Read capabilities and report the actual result.');
  assert.equal(executed,1,JSON.stringify(agent.state.messages.at(-1)));assert.equal(f.requests.length,2);
  assert.equal(f.requests[0].thinking_effort,'extended');assert.ok(JSON.stringify(f.requests[1]).includes('fixture-ready'));
  assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');assert.ok(events.some(e=>e.type==='message_update'));
});

test('invalid desktop output ends in error without executing any tools or resending',{timeout:20000},async t=>{
  const f=fixture(t,()=>({text:'Bad call',toolCalls:[{name:'not_granted',arguments:{}}]}));
  const source=makeStream(config,{},'worker');const result=await (await source(modelFor(config,{}),{messages:[],tools:[]},{fetch:f.fetcher,signal:t.signal})).result();
  assert.equal(result.stopReason,'error');assert.equal(f.requests.length,1);assert.equal(result.content.some(p=>p.type==='toolCall'),false);
});

test('abort stops only its owned desktop request and releases the connection',{timeout:20000},async t=>{
  const f=fixture(t,()=>null),controller=new AbortController();
  const source=makeStream(config,{},'worker');const stream=await source(modelFor(config,{}),{messages:[]},{fetch:f.fetcher,signal:AbortSignal.any([controller.signal,t.signal])});
  for await(const event of stream){if(event.type==='start'){queueMicrotask(()=>controller.abort());break;}}
  const result=await stream.result();assert.equal(result.stopReason,'aborted');
});
