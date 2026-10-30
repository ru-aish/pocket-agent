/** Opt-in, synthetic-only checks against a signed-in native desktop; never run in public CI. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {Agent} from '@earendil-works/pi-agent-core';
import {Type} from 'typebox';
import {makeStream,modelFor} from '../src/provider.js';
import {discoverProviderModels} from '../src/model-catalog.js';
import {PocketRuntime,type NativeBridge} from '../src/runtime.js';
import {emptyAutonomy} from '../src/autonomy.js';

async function main(){
const endpoint=process.env.CHATGPT_DESKTOP_ENDPOINT;
if(!endpoint)throw Error('Set CHATGPT_DESKTOP_ENDPOINT to opt in to real desktop inference.');
const config={mode:'chatgpt-desktop' as const,endpoint,model:process.env.CHATGPT_DESKTOP_MODEL||'gpt-6-instant'};
const stage=process.argv[2]||'text';
const report:any={stage,startedAt:new Date().toISOString(),passed:false};
const began=Date.now();
const messageText=(message:any)=>message.content.filter((part:any)=>part.type==='text').map((part:any)=>part.text).join('\n');
const signal=AbortSignal.timeout(300000);
const text=async(prompt:string,owner='acceptance-main')=>{
  const source=makeStream(config,{},owner);
  const stream=await source(modelFor(config,{}),{messages:[{role:'user',content:[{type:'text',text:prompt}],timestamp:Date.now()}]},{signal});
  const events=[];for await(const event of stream)events.push(event.type);
  const result=await stream.result();assert.equal(result.stopReason,'stop',result.errorMessage);
  return {text:messageText(result),events};
};
try {
  if(stage==='models'){
    const models=await discoverProviderModels(config,{});assert.ok(models.some(model=>model.id===config.model));
    report.models=models;report.count=models.length;
  } else if(stage==='text'){
    const marker=randomUUID();const result=await text('Reply exactly '+marker+'. Do not call tools.');
    assert.ok(result.text.includes(marker));assert.ok(result.events.includes('text_delta'));report.streaming=true;
  } else if(stage==='tools'){
    const nonce=randomUUID();let calls=0;
    const source=makeStream(config,{},'acceptance-tool');
    const agent=new Agent({streamFn:(m,c,o)=>source(m,c,{...o,signal:AbortSignal.any([signal,...(o?.signal?[o.signal]:[])])}),initialState:{model:modelFor(config,{}),
      tools:[{name:'read_fixture',label:'Read fixture',description:'Return a synthetic test receipt.',parameters:Type.Object({}),execute:async()=>{calls++;return {content:[{type:'text',text:nonce}],details:{synthetic:true}};}}]}});
    await agent.prompt('Call read_fixture exactly once, then report its exact returned receipt. Do not invent the receipt.');
    assert.equal(calls,1);const final:any=agent.state.messages.at(-1);assert.equal(final.stopReason,'stop',final.errorMessage);assert.ok(messageText(final).includes(nonce));report.realToolRoundtrip=true;
  } else if(stage==='parallel'){
    const main=randomUUID(),worker=randomUUID();
    const results=await Promise.all([text('Reply exactly '+main,'acceptance-main'),text('Reply exactly '+worker,'acceptance-worker')]);
    assert.ok(results[0].text.includes(main));assert.ok(!results[0].text.includes(worker));assert.ok(results[1].text.includes(worker));assert.ok(!results[1].text.includes(main));report.independentContexts=true;
  } else if(stage==='image'){
    if(!process.env.ACCEPTANCE_IMAGE||!process.env.ACCEPTANCE_IMAGE_CODE)throw Error('Provide the prepared synthetic image path and expected code.');
    const data=(await readFile(process.env.ACCEPTANCE_IMAGE)).toString('base64');
    const stream=await makeStream(config,{},'acceptance-image')(modelFor(config,{}),{messages:[{role:'user',content:[{type:'text',text:'Read the six-character code in this image. Report only that code. Do not call tools.'},{type:'image',data,mimeType:'image/png'}],timestamp:Date.now()}]},{signal});
    const result=await stream.result();assert.equal(result.stopReason,'stop',result.errorMessage);assert.ok(messageText(result).includes(process.env.ACCEPTANCE_IMAGE_CODE));report.blindImageRead=true;
  } else if(stage==='abort'){
    const controller=new AbortController();
    const stream=await makeStream(config,{},'acceptance-abort')(modelFor(config,{}),{messages:[{role:'user',content:[{type:'text',text:'Write a long numbered list of 500 distinct prime numbers with explanations.'}],timestamp:Date.now()}]},{signal:controller.signal});
    for await(const event of stream){if(event.type==='text_delta'||event.type==='thinking_delta'){controller.abort();break;}}
    const result=await stream.result();assert.equal(result.stopReason,'aborted');
    const marker=randomUUID();assert.ok((await text('Reply exactly '+marker,'acceptance-after-abort')).text.includes(marker));report.cancelAndNextRequest=true;
  } else if(stage==='subagent'){
    const nonce=randomUUID();let toolCalls=0;let saved:any={};const events:any[]=[];
    const bridge:NativeBridge={
      call(method,json){
        const args=JSON.parse(json);let result:any={};
        if(method==='credentials.get')result={authMode:'chatgpt-desktop',endpoint,model:config.model,providerRevision:'synthetic-desktop-acceptance'};
        else if(method==='store.load')result=saved;
        else if(method==='store.save'){saved=structuredClone(args.state);result={saved:true};}
        else if(method==='autonomy.load')result=emptyAutonomy();
        else if(method==='autonomy.status')result={enabled:false};
        else if(method==='voice.preferences')result={mode:'text'};
        else if(method==='tool'){
          assert.equal(args.name,'phone_capabilities','Only the synthetic read-only capability fixture is granted.');toolCalls++;result={available:true,operation_prefix:'12345-0123456789abcdef',synthetic_receipt:nonce};
        }
        return JSON.stringify({ok:true,result});
      },emit(json){events.push(JSON.parse(json));},
    };
    const runtime=new PocketRuntime(bridge);
    try {
      const command=JSON.parse(runtime.dispatch(JSON.stringify({action:'send',text:'Use the subagent tool to spawn exactly one phone-operator worker. Assign it to call only phone_capabilities once, read synthetic_receipt, and report that exact receipt. This is a synthetic provider test; do not navigate or mutate the phone. End your turn after dispatch; when the host delivers the worker result, report its exact receipt. Do not call phone_capabilities from main.'})));
      assert.equal(command.ok,true);
      await new Promise<void>((resolve,reject)=>{
        const check=()=>{
          const state=runtime.state();
          if(state.lastError){clearInterval(timer);reject(Error(state.lastError));return;}
          if(state.tasks.some((task:any)=>task.status==='completed')&&!state.busy&&JSON.stringify(state.messages).includes(nonce)){clearInterval(timer);resolve();}
        };
        const timer=setInterval(check,250);signal.addEventListener('abort',()=>{clearInterval(timer);reject(Error('Subagent acceptance timed out.'));},{once:true});check();
      });
      assert.equal(toolCalls,1);assert.ok(events.some(event=>event.type==='worker'));report.pocketAgentWorkerCompleted=true;
    } finally{runtime.dispatch(JSON.stringify({action:'stop_all'}));runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));}
  } else throw Error('Unknown acceptance stage.');
  report.passed=true;
} catch(error){report.error=error instanceof Error?error.message:'Acceptance failed.';process.exitCode=1;}
report.elapsedSeconds=(Date.now()-began)/1000;
if(process.env.ACCEPTANCE_REPORT)await writeFile(process.env.ACCEPTANCE_REPORT,JSON.stringify(report,null,2));
console.log(JSON.stringify(report));

}
void main().catch(error=>{console.error(error instanceof Error?error.message:"Acceptance failed.");process.exitCode=1;});
