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
   assert.equal(body.model,'gpt-6-luna');assert.equal(body.stream,true);assert.equal(body.store,false);
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

test('direct Codex discards remote error details without retrying or leaking credentials',async()=>{
 const credentials=accountCredential();let calls=0;
 const fetcher:typeof fetch=async()=>{calls++;return new Response(('private remote body '+credentials.refreshToken+' '+credentials.idToken+' ').repeat(20000),{status:429});};
 const result=await(await makeStream(accountConfig,credentials,'main')(modelFor(accountConfig,credentials),{messages:[]},{fetch:fetcher})).result();
 assert.equal(calls,1);assert.equal(result.stopReason,'error');
 assert.ok((result.errorMessage?.length||0)<512);
 for(const secret of ['private remote body',credentials.refreshToken,credentials.idToken,credentials.accessToken,credentials.accountId])assert.ok(!JSON.stringify(result).includes(secret));
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
 const nebius=modelFor({mode:'nebius',model:'Qwen/Qwen3-30B-A3B'},{authMode:'nebius',apiKey:'secret',model:'Qwen/Qwen3-30B-A3B'});
 assert.equal(nebius.api,'openai-completions');
 assert.equal(nebius.provider,'nebius');
 assert.equal(nebius.baseUrl,'https://api.studio.nebius.ai/v1');
 assert.throws(()=>modelFor({mode:'opencode',endpoint:'https://evil.test',model:'big-pickle'},{authMode:'opencode'}),/fixed API endpoint/);
 assert.throws(()=>modelFor({mode:'nebius',endpoint:'https://evil.test',model:'Qwen\/Qwen3-30B-A3B'},{authMode:'nebius',apiKey:'secret'}),/fixed API endpoint/);
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
   {config:{mode:'nebius' as const,model:'Qwen/Qwen3-30B-A3B'},credentials:{authMode:'nebius',apiKey:'nebius-secret',model:'Qwen/Qwen3-30B-A3B'},url:'https://api.studio.nebius.ai/v1/chat/completions',key:'nebius-secret'},
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
