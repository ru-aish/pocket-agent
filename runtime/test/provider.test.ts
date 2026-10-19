import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {Agent} from '@earendil-works/pi-agent-core';
import {makeStream,modelFor} from '../src/provider.js';
import {SubagentParams} from '../vendor/pi9/schema.js';

test('Responses wire preserves optional Pi9 action fields by explicitly disabling strict normalization',async()=>{
 let wire:any;
 const server=createServer(async(req,res)=>{
   let body='';for await(const chunk of req)body+=chunk;
   wire=JSON.parse(body);
   res.writeHead(200,{'content-type':'text/event-stream'});
   res.end('event: response.completed\ndata: '+JSON.stringify({type:'response.completed',response:{id:'resp_contract',status:'completed',output:[],usage:{input_tokens:1,output_tokens:0,total_tokens:1,input_tokens_details:{cached_tokens:0}}}})+'\n\n');
 });
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
 const address=server.address();assert.ok(address&&typeof address!=='string');
 const config={mode:'codex' as const,endpoint:`http://127.0.0.1:${address.port}/v1`,model:'gpt-6-luna'};
 const agent=new Agent({streamFn:makeStream(config,{apiKey:'synthetic-contract-credential'},'main'),initialState:{model:modelFor(config),tools:[{name:'subagent',label:'Subagent',description:'Dispatch Pi9 work.',parameters:SubagentParams,execute:async()=>({content:[{type:'text',text:'unused'}],details:{}})}]}});
 try {
   await agent.prompt('Wire contract probe');
   assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');
   const tool=wire.tools.find((item:any)=>item.name==='subagent');
   assert.ok(tool,'the actual Pi Responses request declares the selected Pi9 tool');
   assert.ok(Object.hasOwn(tool,'strict'),'strict must be explicit on the wire');
   assert.equal(tool.strict,false,'Pi9 discriminated actions rely on optional fields');
   assert.deepEqual(tool.parameters.required,['action']);
   for(const optional of ['statuses','collected','spawns','resumes','messages','subagentIds']) {
     assert.ok(Object.hasOwn(tool.parameters.properties,optional));
     assert.ok(!tool.parameters.required.includes(optional));
   }
   assert.deepEqual(tool.parameters.properties.spawns.items.required,['agent','prompt','label']);
 } finally {
   server.closeAllConnections();
   await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));
 }
});

import * as zlib from 'node:zlib';
import {CODEX_RESPONSES_URL,CODEX_TOKEN_URL,registerCodexCredentialUpdater} from '../src/codex-auth.js';
const accountConfig={mode:'codex-account' as const,model:'gpt-6-luna'};
function accountCredential(expiry=Date.now()+3600000) {
 const accountId='synthetic-account';
 const accessToken=[Buffer.from('{}').toString('base64url'),Buffer.from(JSON.stringify({exp:Math.floor(expiry/1000),'https://api.openai.com/auth':{chatgpt_account_id:accountId}})).toString('base64url'),'signature'].join('.');
 return {authMode:'codex-account',accessToken,refreshToken:'synthetic-refresh-secret',idToken:'synthetic-id-secret',accountId,expiresAt:Math.floor(expiry/1000)*1000};
}
function requestBody(init?:RequestInit) {
 let bytes=typeof init?.body==='string'?Buffer.from(init.body):Buffer.from(init?.body as any);
 if(new Headers(init?.headers).get('content-encoding')==='zstd')bytes=(zlib as any).zstdDecompressSync(bytes);
 return JSON.parse(bytes.toString('utf8'));
}
function codexResponse(output:any[]) {
 const event={type:'response.completed',response:{id:'resp_direct',status:'completed',output,usage:{input_tokens:10,output_tokens:4,total_tokens:14,input_tokens_details:{cached_tokens:0}}}};
 const events=[...output.map((item,output_index)=>({type:'response.output_item.done',output_index,item})),event];
 return new Response(events.map(item=>'event: '+item.type+'\ndata: '+JSON.stringify(item)+'\n\n').join(''),{headers:{'content-type':'text/event-stream'}});
}

test('direct Codex wire has fixed destination, explicit strict:false and a structured tool result roundtrip',async()=>{
 const credentials=accountCredential();let calls=0,executions=0;const wires:any[]=[];let wireError:any;
 const fetcher:typeof fetch=async(input,init)=>{
   try {
   calls++;assert.equal(String(input),CODEX_RESPONSES_URL);assert.equal(init?.redirect,'error');assert.ok(init?.signal);
   const headers=new Headers(init?.headers);assert.equal(headers.get('authorization'),'Bearer '+credentials.accessToken);assert.equal(headers.get('chatgpt-account-id'),credentials.accountId);
   const body=requestBody(init);wires.push(body);
   assert.equal(body.model,'gpt-6-luna');assert.equal(body.stream,true);assert.equal(body.store,false);assert.equal(body.reasoning?.effort,'low');
   assert.equal(body.tools[0].strict,false);assert.deepEqual(body.tools[0].parameters.required,['action']);
   if(calls===1)return codexResponse([{type:'function_call',id:'fc_contract',call_id:'call_contract',name:'subagent',arguments:'{"action":"agents"}',status:'completed'}]);
   assert.ok(body.input.some((item:any)=>item.type==='function_call_output'&&item.call_id==='call_contract'&&item.output.includes('synthetic-ready')));
   return codexResponse([{type:'message',id:'msg_contract',role:'assistant',status:'completed',content:[{type:'output_text',text:'Ready.',annotations:[]}]}]);
   } catch(error) {wireError=error;throw error;}
 };
 const stream=makeStream(accountConfig,credentials,'main');
 const agent=new Agent({streamFn:(model,context,options)=>stream(model,context,{...options,fetch:fetcher}),initialState:{model:modelFor(accountConfig,credentials),tools:[{name:'subagent',label:'Subagent',description:'Inspect workers.',parameters:SubagentParams,execute:async()=>{executions++;return {content:[{type:'text',text:'synthetic-ready'}],details:{ready:true}};}}]}});
 await agent.prompt('Read the available agents and report ready.');
 assert.ifError(wireError);assert.equal(calls,2,'final '+agent.state.messages.at(-1)?.stopReason);assert.equal(executions,1);assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');
 assert.equal(agent.state.messages.at(-1)?.content[0].type,'text');
});

test('direct Codex uses the selected reasoning effort',async()=>{
 const credentials={...accountCredential(),providerRevision:'rev-1',reasoningProviderRevision:'rev-1',reasoningModel:'gpt-6-luna',reasoningLevels:['low','medium','high','xhigh'] as any},config={...accountConfig,reasoning:'high' as const};let wire:any;
 const stream=makeStream(config,credentials,'main');
 const result=await(await stream(modelFor(config,credentials),{messages:[]},{fetch:async(_input,init)=>{
   wire=requestBody(init);return codexResponse([]);
 }})).result();
 assert.equal(result.stopReason,'stop');
 assert.equal(wire.reasoning?.effort,'high');
});

test('worker sessions keep the existing low reasoning default',async()=>{
 const credentials={...accountCredential(),reasoning:'high' as const,providerRevision:'rev-1',reasoningProviderRevision:'rev-1',reasoningModel:'gpt-6-luna',reasoningLevels:['low','medium','high','xhigh'] as any};
 const config={...accountConfig,reasoning:'high' as const};let wire:any;
 const result=await(await makeStream(config,credentials,'phone-operator')(modelFor(config,credentials),{messages:[]},{fetch:async(_input,init)=>{
   wire=requestBody(init);return codexResponse([]);
 }})).result();
 assert.equal(result.stopReason,'stop');
 assert.equal(wire.reasoning?.effort,'low');
});

test('direct Codex rejects unsupported advertised reasoning instead of silently clamping',async()=>{
 const credentials={...accountCredential(),providerRevision:'rev-1',reasoningProviderRevision:'rev-1',reasoningModel:'gpt-6-luna',reasoningLevels:['low','medium'] as any};
 const config={...accountConfig,reasoning:'xhigh' as const};let calls=0;
 const result=await(await makeStream(config,credentials,'main')(modelFor(config,credentials),{messages:[]},{fetch:async()=>{calls++;return codexResponse([]);}})).result();
 assert.equal(calls,0);
 assert.equal(result.stopReason,'error');
 assert.match(result.errorMessage??'',/not supported/);
});

test('stale reasoning capabilities from another provider revision are ignored',async()=>{
 const config={mode:'openai' as const,endpoint:'https://api.openai.com/v1',model:'gpt-6-sol',reasoning:'xhigh' as const};
 const credentials={authMode:'openai',apiKey:'synthetic-key',providerRevision:'new-provider',reasoningProviderRevision:'old-provider',
   reasoningModel:'gpt-6-sol',reasoningLevels:['low','medium','high','xhigh'] as any};
 let calls=0;
 const result=await(await makeStream(config,credentials,'main')(modelFor(config,credentials),{messages:[]},{fetch:async()=>{calls++;return codexResponse([]);}})).result();
 assert.equal(calls,0);
 assert.equal(result.stopReason,'error');
 assert.match(result.errorMessage??'',/Extra high reasoning requires discovered model support/);
});

test('unknown extra-high support is rejected instead of being silently clamped',async()=>{
 const config={mode:'openai' as const,endpoint:'https://api.openai.com/v1',model:'gpt-6-sol',reasoning:'xhigh' as const};
 let calls=0;
 const result=await(await makeStream(config,{authMode:'openai',apiKey:'synthetic-key'},'main')(modelFor(config,{authMode:'openai',apiKey:'synthetic-key'}),{messages:[]},{fetch:async()=>{calls++;return codexResponse([]);}})).result();
 assert.equal(calls,0);
 assert.equal(result.stopReason,'error');
 assert.match(result.errorMessage??'',/Extra high reasoning requires discovered model support/);
});

test('direct Codex requires discovered capabilities before non-low reasoning',async()=>{
 const credentials=accountCredential(),config={...accountConfig,reasoning:'high' as const};let calls=0;
 const result=await(await makeStream(config,credentials,'main')(modelFor(config,credentials),{messages:[]},{fetch:async()=>{calls++;return codexResponse([]);}})).result();
 assert.equal(calls,0);
 assert.equal(result.stopReason,'error');
 assert.match(result.errorMessage??'',/Discover models/);
});

test('direct Codex rejects every configured endpoint override',()=>{
 for(const endpoint of ['https://evil.test','https://chatgpt.com/backend-api/codex/responses','http://127.0.0.1:8787/v1']) {
   assert.throws(()=>modelFor({...accountConfig,endpoint},accountCredential()),/fixed provider endpoint/);
   assert.throws(()=>modelFor(accountConfig,{...accountCredential(),endpoint}),/fixed provider endpoint/);
 }
 assert.equal(modelFor(accountConfig,accountCredential()).baseUrl,CODEX_RESPONSES_URL);
});

test('direct Codex never infers before refreshed credentials are durably saved',async()=>{
 const credentials=accountCredential(Date.now()-10000);let calls=0;
 registerCodexCredentialUpdater(credentials,()=>{throw Error('private persistence details '+credentials.refreshToken);});
 const fetcher:typeof fetch=async input=>{
   calls++;assert.equal(String(input),CODEX_TOKEN_URL);
   const next=accountCredential(Date.now()+7200000);
   return Response.json({access_token:next.accessToken,refresh_token:'rotated-private-secret',expires_in:7200});
 };
 const stream=makeStream(accountConfig,credentials,'main');
 for(let i=0;i<2;i++) {
   const result=await(await stream(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher})).result();
   assert.equal(result.stopReason,'error');assert.ok(!JSON.stringify(result).includes('private persistence'));
 }
 assert.equal(calls,1,'uncertain rotation/persistence failure must not be retried');
});

test('direct Codex discards oversized remote error details without retrying or leaking credentials',async()=>{
 const credentials=accountCredential();let calls=0;
 const fetcher:typeof fetch=async()=>{calls++;return new Response(('private remote body '+credentials.refreshToken+' '+credentials.idToken+' ').repeat(20000),{status:429});};
 const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher})).result();
 assert.equal(calls,1);assert.equal(result.stopReason,'error');
 assert.ok((result.errorMessage?.length||0)<512);
 for(const secret of ['private remote body',credentials.refreshToken,credentials.idToken,credentials.accessToken,credentials.accountId])assert.ok(!JSON.stringify(result).includes(secret));
});

test('Codex retries transient inference HTTP failures within a three-attempt bound',async()=>{
 for(const status of [408,429,500,502,503,504]) {
   const credentials=accountCredential();let calls=0;
   const fetcher:typeof fetch=async()=>++calls<3
     ? new Response('private upstream diagnostic',{status,headers:{'retry-after':'0'}})
     : codexResponse([]);
   const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher})).result();
   assert.equal(calls,3);assert.equal(result.stopReason,'stop');
 }
 const credentials=accountCredential();let calls=0;
 const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:async()=>{
   calls++;return new Response('private '+credentials.refreshToken,{status:503,headers:{'retry-after-ms':'0'}});
 }})).result();
 assert.equal(calls,3);assert.equal(result.stopReason,'error');assert.ok(!JSON.stringify(result).includes(credentials.refreshToken));
});

test('Codex does not retry permanent HTTP errors, quota exhaustion, or excessive Retry-After',async()=>{
 for(const response of [
   ...[400,401,403,404,422,501].map(status=>()=>new Response('private body',{status})),
   ()=>Response.json({error:{code:'insufficient_quota',message:'private body'}},{status:429}),
   ()=>Response.json({error:{type:'usage_limit_reached',message:'private body'}},{status:429}),
   ()=>Response.json({error:{type:'usage_not_included',message:'private body'}},{status:429}),
   ()=>new Response('private body',{status:503,headers:{'retry-after':'60'}}),
 ]) {
   const credentials=accountCredential();let calls=0;
   const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:async()=>{calls++;return response();}})).result();
   assert.equal(calls,1);assert.equal(result.stopReason,'error');assert.ok(!JSON.stringify(result).includes('private body'));
 }
});

test('Codex retries classified network failures but not arbitrary exceptions or TLS errors',async()=>{
 for(const error of [new TypeError('fetch failed'),Object.assign(new Error('private socket failure'),{cause:{code:'ECONNRESET'}}),new Error('callback failed'),Object.assign(new TypeError('fetch failed'),{cause:{code:'CERT_HAS_EXPIRED'}})]) {
   const credentials=accountCredential();let calls=0;
   const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:async()=>{if(++calls===1)throw error;return codexResponse([]);}})).result();
   const retries=(error instanceof TypeError && !(error as any).cause) || (error as any).cause?.code==='ECONNRESET';
   assert.equal(calls,retries?2:1);assert.equal(result.stopReason,retries?'stop':'error');
   assert.ok(!JSON.stringify(result).includes('private socket failure'));
 }
});

test('Codex cancellation interrupts inference backoff without another dispatch', {timeout:3000},async()=>{
 const credentials=accountCredential(),abort=new AbortController();let calls=0;
 const stream=await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{signal:abort.signal,fetch:async()=>{
   calls++;setTimeout(()=>abort.abort(),20);return new Response('private',{status:503,headers:{'retry-after':'5'}});
 }});
 assert.equal((await stream.result()).stopReason,'aborted');assert.equal(calls,1);
});

test('Codex inference deadline covers retry backoff and stalled response bodies', {timeout:3000},async()=>{
 const originalTimeout=AbortSignal.timeout;
 try {
   for(const stall of ['backoff','stream','error-body']) {
     const deadline=new AbortController();(AbortSignal as any).timeout=()=>deadline.signal;
     const credentials=accountCredential();let calls=0,bodyAborted=false;
     const stream=await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:async(_input,init)=>{
       calls++;setTimeout(()=>deadline.abort(),20);
       if(stall==='backoff')return new Response('private',{status:503,headers:{'retry-after':'5'}});
       return new Response(new ReadableStream({start(controller){
         init?.signal?.addEventListener('abort',()=>{bodyAborted=true;controller.error(new Error('timeout'));},{once:true});
       }}),{status:stall==='error-body'?503:200});
     }});
     assert.equal((await stream.result()).stopReason,'error');assert.equal(calls,1);
     if(stall!=='backoff')assert.equal(bodyAborted,true);
   }
 } finally {(AbortSignal as any).timeout=originalTimeout;}
});

test('Codex does not restart SSE after tool output or provider stream failure',async()=>{
 for(const partialTool of [false,true]) {
   const credentials=accountCredential();let calls=0,executions=0;
   const fetcher:typeof fetch=async()=>{
     calls++;
     const events=partialTool?[
       {type:'response.output_item.added',output_index:0,item:{type:'function_call',id:'fc_partial',call_id:'call_partial',name:'subagent',arguments:''}},
       {type:'response.function_call_arguments.delta',output_index:0,delta:'{"action":"agents"}'},
       {type:'response.output_item.done',output_index:0,item:{type:'function_call',id:'fc_partial',call_id:'call_partial',name:'subagent',arguments:'{"action":"agents"}',status:'completed'}},
     ]:[];
     events.push({type:'response.failed',response:{error:{code:'server_error',message:'private '+credentials.refreshToken}}} as any);
     return new Response(events.map(event=>'data: '+JSON.stringify(event)+'\n\n').join(''));
   };
   const stream=makeStream(accountConfig,credentials,'main');
   const agent=new Agent({streamFn:(model,context,options)=>stream(model,context,{...options,fetch:fetcher}),initialState:{model:modelFor(accountConfig,credentials),tools:[{name:'subagent',label:'Subagent',description:'Inspect workers.',parameters:SubagentParams,execute:async()=>{executions++;return {content:[],details:{}};}}]}});
   await agent.prompt('Inspect workers.');
   assert.equal(calls,1);assert.equal(executions,0);assert.equal(agent.state.messages.at(-1)?.stopReason,'error');
   assert.ok(!JSON.stringify(agent.state.messages).includes(credentials.refreshToken));
 }
});

test('Codex retries only the current inference and never replays completed tool side effects',async()=>{
 const credentials=accountCredential();let calls=0,executions=0;const wires:any[]=[];
 const fetcher:typeof fetch=async(_input,init)=>{
   const body=requestBody(init);wires.push(body);calls++;
   if(calls===1)return new Response('temporary',{status:503,headers:{'retry-after':'0'}});
   if(calls===2)return codexResponse([{type:'function_call',id:'fc_once',call_id:'call_once',name:'subagent',arguments:'{"action":"agents"}',status:'completed'}]);
   if(calls===3)throw new TypeError('fetch failed');
   return codexResponse([]);
 };
 const stream=makeStream(accountConfig,credentials,'main');
 const agent=new Agent({streamFn:(model,context,options)=>stream(model,context,{...options,fetch:fetcher}),initialState:{model:modelFor(accountConfig,credentials),tools:[{name:'subagent',label:'Subagent',description:'Inspect workers.',parameters:SubagentParams,execute:async()=>{executions++;return {content:[{type:'text',text:'saved-result'}],details:{}};}}]}});
 await agent.prompt('Inspect workers.');
 assert.equal(calls,4);assert.equal(executions,1);assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');
 assert.deepEqual(wires[0],wires[1]);assert.deepEqual(wires[2],wires[3]);
 assert.ok(wires[3].input.some((item:any)=>item.type==='function_call_output'&&item.output.includes('saved-result')));
});

test('direct Codex streaming abort propagates to the fixed-origin fetch body', {timeout:3000},async()=>{
 const credentials=accountCredential(),abort=new AbortController();let upstreamAborted=false;
 const fetcher:typeof fetch=async(_input,init)=>new Response(new ReadableStream({
   start(controller) {
     controller.enqueue(new TextEncoder().encode('event: response.created\ndata: {"type":"response.created","response":{"id":"resp_waiting"}}\n\n'));
     init?.signal?.addEventListener('abort',()=>{upstreamAborted=true;controller.error(new DOMException('aborted','AbortError'));},{once:true});
   },
 }),{headers:{'content-type':'text/event-stream'}});
 const stream=await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher,signal:abort.signal});
 for await(const event of stream)if(event.type==='start')abort.abort();
 assert.equal((await stream.result()).stopReason,'aborted');assert.equal(upstreamAborted,true);
});

test('direct Codex bounds its streamed response and cancels an oversized body',async()=>{
 const credentials=accountCredential();let cancelled=false;
 const fetcher:typeof fetch=async()=>new Response(new ReadableStream({
   start(controller){controller.enqueue(new Uint8Array(16*1024*1024+1));},cancel(){cancelled=true;},
 }));
 const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher})).result();
 assert.equal(result.stopReason,'error');assert.equal(cancelled,true);
});


test('OpenCode and Nebius are fixed Chat Completions providers',()=>{
 const open=modelFor({mode:'opencode',model:'big-pickle'},{authMode:'opencode',model:'big-pickle'});
 assert.equal(open.api,'openai-completions');
 assert.equal(open.provider,'opencode');
 assert.equal(open.baseUrl,'https://opencode.ai/inference/openai/v1');
 assert.deepEqual(open.input,['text']);
 const openVision=modelFor({mode:'opencode',model:'nemotron-3.5-super-vl-free'},{authMode:'opencode',model:'nemotron-3.5-super-vl-free'});
 assert.deepEqual(openVision.input,['text','image']);
 const nebius=modelFor({mode:'nebius',model:'zai-org/GLM-5.3-Flash'},{authMode:'nebius',apiKey:'secret',model:'zai-org/GLM-5.3-Flash'});
 assert.equal(nebius.api,'openai-completions');
 assert.equal(nebius.provider,'nebius');
 assert.equal(nebius.baseUrl,'https://api.tokenfactory.nebius.com/v1');
 assert.deepEqual(nebius.input,['text','image']);
 const textOnly=modelFor({mode:'nebius',model:'Qwen/Qwen3-30B-A3B-Instruct-2507'},{authMode:'nebius',apiKey:'secret',model:'Qwen/Qwen3-30B-A3B-Instruct-2507'});
 assert.deepEqual(textOnly.input,['text']);
 const vl=modelFor({mode:'nebius',model:'Qwen/Qwen3-VL-30B-A3B'},{authMode:'nebius',apiKey:'secret',model:'Qwen/Qwen3-VL-30B-A3B'});
 assert.deepEqual(vl.input,['text','image']);
 assert.throws(()=>modelFor({mode:'opencode',endpoint:'https://evil.test',model:'big-pickle'},{authMode:'opencode'}),/fixed API endpoint/);
 assert.throws(()=>modelFor({mode:'nebius',endpoint:'https://evil.test',model:'zai-org/GLM-5.3-Flash'},{authMode:'nebius',apiKey:'secret'}),/fixed API endpoint/);
});

test('OpenCode refuses inference until an external Console key is configured',async()=>{
 const config={mode:'opencode' as const,model:'big-pickle'};
 const credentials={authMode:'opencode',apiKey:'',model:'big-pickle'};
 let called=false;
 const result=await(await makeStream(config,credentials,'main')(modelFor(config,credentials),{messages:[]},{fetch:async()=>{called=true;throw Error('unexpected');}})).result();
 assert.equal(called,false);
 assert.equal(result.stopReason,'error');
 assert.match(result.errorMessage||'',/Console service-account key/);
});

test('keyed OpenCode and Nebius inference send the configured bearer credential',async()=>{
 const cases=[
   {config:{mode:'opencode' as const,model:'beam-free'},credentials:{authMode:'opencode',apiKey:'oc_sk_test_external',model:'beam-free'},url:'https://opencode.ai/inference/openai/v1/chat/completions',key:'oc_sk_test_external'},
   {config:{mode:'nebius' as const,model:'zai-org/GLM-5.3-Flash'},credentials:{authMode:'nebius',apiKey:'nebius-secret',model:'zai-org/GLM-5.3-Flash'},url:'https://api.tokenfactory.nebius.com/v1/chat/completions',key:'nebius-secret'},
 ];
 for(const item of cases) {
   let authorization:string|null=null;
   const fetcher:typeof fetch=async(input,init)=>{
     assert.equal(String(input),item.url);
     assert.equal(init?.redirect,'error');
     const headers=new Headers(init?.headers);
     authorization=headers.get('authorization');
     assert.match(headers.get('content-type')||'',/^application\/json/);
     if(item.config.mode==='opencode')assert.equal(headers.get('x-opencode-org-id'),null);
     const chunks=[
       {id:'chatcmpl-keyed',object:'chat.completion.chunk',created:1,model:item.credentials.model,choices:[{index:0,delta:{role:'assistant',content:'OK'},finish_reason:null}]},
       {id:'chatcmpl-keyed',object:'chat.completion.chunk',created:1,model:item.credentials.model,choices:[{index:0,delta:{},finish_reason:'stop'}]},
     ];
     return new Response(chunks.map(x=>'data: '+JSON.stringify(x)+'\n\n').join('')+'data: [DONE]\n\n',{headers:{'content-type':'text/event-stream'}});
   };
   const stream=makeStream(item.config,item.credentials,'main');
   const result=await(await stream(modelFor(item.config,item.credentials),{messages:[{role:'user',content:[{type:'text',text:'Say OK'}],timestamp:Date.now()}]},{fetch:fetcher})).result();
   assert.equal(authorization,'Bearer '+item.key);
   assert.equal(result.stopReason,'stop');
 }
});


test('fixed OpenCode timeout remains active while the SSE response body is stalled',{timeout:3000},async()=>{
 const originalTimeout=AbortSignal.timeout;
 const timeoutController=new AbortController();
 let wireSignal:AbortSignal|undefined,bodyAborted=false;
 let readyResolve!:()=>void;
 const ready=new Promise<void>(resolve=>{readyResolve=resolve;});
 (AbortSignal as any).timeout=(ms:number)=>{
   assert.equal(ms,120000);
   return timeoutController.signal;
 };
 try {
   const config={mode:'opencode' as const,model:'beam-free'};
   const credentials={authMode:'opencode',apiKey:'open-secret',model:'beam-free'};
   const fetcher:typeof fetch=async(_input,init)=>{
     wireSignal=init?.signal as AbortSignal|undefined;
     assert.ok(wireSignal);
     const body=new ReadableStream<Uint8Array>({
       start(controller) {
         controller.enqueue(new TextEncoder().encode('data: '+JSON.stringify({
           id:'chatcmpl-stall',object:'chat.completion.chunk',created:1,model:'beam-free',
           choices:[{index:0,delta:{role:'assistant',content:'partial'},finish_reason:null}],
         })+'\n\n'));
         wireSignal!.addEventListener('abort',()=>{
           bodyAborted=true;
           controller.error(new DOMException('timed out','AbortError'));
         },{once:true});
         readyResolve();
       },
     });
     return new Response(body,{headers:{'content-type':'text/event-stream'}});
   };
   const stream=await makeStream(config,credentials,'main')(
     modelFor(config,credentials),
     {messages:[{role:'user',content:[{type:'text',text:'stall'}],timestamp:Date.now()}]},
     {fetch:fetcher},
   );
   await Promise.race([
     ready,
     new Promise<never>((_,reject)=>setTimeout(()=>reject(new Error('provider fetch did not start')),1000)),
   ]);
   timeoutController.abort();
   const result=await Promise.race([
     stream.result(),
     new Promise<never>((_,reject)=>setTimeout(()=>reject(new Error('provider stream did not stop after timeout')),1000)),
   ]);
   assert.equal(bodyAborted,true);
   assert.equal(wireSignal?.aborted,true);
   assert.equal(result.stopReason,'error');
 } finally {
   (AbortSignal as any).timeout=originalTimeout;
 }
});
