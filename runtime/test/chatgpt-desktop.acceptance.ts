/** Opt-in inference checks. Tool/worker acceptance requires the real phone MCP connector, not a synthetic JSON protocol. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {makeStream,modelFor} from '../src/provider.js';
import {discoverProviderModels} from '../src/model-catalog.js';

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
  } else throw Error('Unknown acceptance stage.');
  report.passed=true;
} catch(error){report.error=error instanceof Error?error.message:'Acceptance failed.';process.exitCode=1;}
report.elapsedSeconds=(Date.now()-began)/1000;
if(process.env.ACCEPTANCE_REPORT)await writeFile(process.env.ACCEPTANCE_REPORT,JSON.stringify(report,null,2));
console.log(JSON.stringify(report));

}
void main().catch(error=>{console.error(error instanceof Error?error.message:"Acceptance failed.");process.exitCode=1;});
