import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime,NativeBridge,DEFAULT_MAIN_PROMPT} from '../src/runtime.js';
import {delay} from '../src/scripted.js';
import {AssistantMessageEventStream,validateToolArguments} from '@earendil-works/pi-ai';
import {toolResult,MobileSubagentParams,nativeTools} from '../src/tools.js';
import {SubagentParams,parseSubagentInvocation} from '../vendor/pi9/schema.js';

class Bridge implements NativeBridge {
 state:any={};events:any[]=[];tools:any[]=[];credentials:any={authMode:'scripted'};
 call(method:string,argsJson:string):string {
  const args=JSON.parse(argsJson);let result:any;
  if(method==='credentials.get')result=this.credentials;
  if(method==='store.load')result=this.state;
  if(method==='store.save'){this.state=structuredClone(args.state);result={saved:true};}
  if(method==='tool'){this.tools.push(args);result={available:true,operation_prefix:'20741-0123456789abcdef',owner:args.owner};}
  return JSON.stringify({ok:true,result});
 }
 emit(json:string):void {this.events.push(JSON.parse(json));}
}
async function until(fn:()=>any,timeout=4000):Promise<void>{const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)throw new Error('Timed out');await delay(10);}}
const command=(r:PocketRuntime,cmd:any)=>assert.equal(JSON.parse(r.dispatch(JSON.stringify(cmd))).ok,true);

test('Pi validates the full mutation ID contract before dispatch; historical receipt lookup remains separate',()=>{
 const tools=nativeTools({call:()=>{throw new Error('Validation must not dispatch');}},'contract');
 const open=tools.find(t=>t.name==='phone_open_app')!;
 const validate=(id:string)=>validateToolArguments(open,{type:'toolCall',id:'contract-call',name:open.name,arguments:{package_name:'dev.pocket.agent.fixture',operation_id:id}});
 const prefix='20741-0123456789abcdef_';
 for(const suffix of ['type001','x'.repeat(49),'invalid.nonce','unicode_é'])assert.throws(()=>validate(prefix+suffix),/operation_id/);
 for(const suffix of ['type0001','x'.repeat(48),'owner_gen1_type_0001'])assert.equal(validate(prefix+suffix).operation_id,prefix+suffix);
 const lookup=tools.find(t=>t.name==='phone_operation')!;
 assert.equal(validateToolArguments(lookup,{type:'toolCall',id:'history-call',name:lookup.name,arguments:{operation_id:'old-history-id'}}).operation_id,'old-history-id');
});

test('mobile spawn schema omits unsupported overrides while upstream actions and inherited grants stay intact',()=>{
 const mobile:any=MobileSubagentParams,upstream:any=SubagentParams;
 assert.deepEqual(Object.keys(mobile.properties.spawns.items.properties).sort(),['agent','label','model','prompt']);
 assert.deepEqual(mobile.properties.spawns.items.required,['agent','prompt','label']);
 assert.equal(mobile.properties.spawns.items.additionalProperties,false);
 for(const field of ['cwd','skills','thinking'])assert.ok(field in upstream.properties.spawns.items.properties,'upstream schema must remain unmodified');
 for(const field of Object.keys(upstream.properties).filter(x=>x!=='spawns'))assert.deepEqual(mobile.properties[field],upstream.properties[field]);
 assert.ok(!('error' in parseSubagentInvocation({action:'spawn',spawns:[{agent:'phone-operator',label:'Upstream contract',prompt:'Read state',model:'configured-model',cwd:'.',skills:[],thinking:'off'}]})));
 const runtime=new PocketRuntime(new Bridge());
 const tool=runtime.main.agent.state.tools.find((t:any)=>t.name==='subagent')!;
 assert.equal(tool.parameters,MobileSubagentParams);
 assert.match(tool.description,/Tools are inherited by children/);
 assert.ok(runtime.registry.agents.get('phone-operator')!.tools!.includes('phone_act'));
 assert.ok(!runtime.registry.agents.get('researcher')!.tools!.includes('phone_act'));
 assert.ok(runtime.registry.agents.get('researcher')!.tools!.includes('phone_calendar_events'));
});

test('batch vision keeps only latest bounded image and preserves image-free operation receipts',()=>{
 const first='Zmlyc3Qtc2NyZWVu',latest='bGF0ZXN0LXNjcmVlbg==';
 const result=toolResult({status:'completed',results:[
   {index:0,name:'phone_observe',result:{snapshot_id:'screen-1',nodes:[{text:'First screen'}],images:[{data:first,mimeType:'image/png'}]}},
   {index:1,name:'phone_act',result:{operation_id:'fixture_action001',status:'verified',receipt:{delivered:true}}},
   {index:2,name:'phone_observe',result:{snapshot_id:'screen-2',nodes:[{text:'Latest screen'}],images:[{data:latest,mimeType:'image/jpeg'}]}},
 ]});
 assert.deepEqual(result.content.filter((c:any)=>c.type==='image'),[{type:'image',data:latest,mimeType:'image/jpeg'}]);
 const text=result.content.find((c:any)=>c.type==='text').text;
 for(const value of [text,JSON.stringify(result.details)]) {
   assert.ok(!value.includes(first));assert.ok(!value.includes(latest));assert.ok(!value.includes('"images"'));
 }
 assert.deepEqual(JSON.parse(text),result.details);
 assert.deepEqual(result.details.results[1].result,{operation_id:'fixture_action001',status:'verified',receipt:{delivered:true}});
 assert.equal(result.details.results[0].result.nodes[0].text,'First screen');
 assert.equal(result.details.results[2].result.snapshot_id,'screen-2');
 const oversized=toolResult({results:[{result:{images:[{data:first,mimeType:'image/png'}]}},{result:{images:[{data:'X'.repeat(2000001),mimeType:'image/png'}],operation_id:'retained'}}]});
 assert.equal(oversized.content.filter((c:any)=>c.type==='image').length,0,'oversized latest image does not resurrect a stale screenshot');
 assert.equal(oversized.details.results[1].result.operation_id,'retained');
 assert.ok(JSON.stringify(oversized).length<1000);
});

test('fresh observations replace prior live screenshots; terminal runs retain text/results without base64',async()=>{
 const imageData='c2NyZWVuc2hvdA=='.repeat(80000); // >1 MB must not consume the text context budget.
 const images=(messages:any[])=>messages.flatMap(m=>Array.isArray(m.content)?m.content:[]).filter(c=>c.type==='image');
 let r:PocketRuntime;
 class ImageBridge extends Bridge {
   observations=0;priorImageCounts:number[]=[];
   override call(method:string,json:string):string {
     const args=JSON.parse(json);
     if(method==='tool'&&args.name==='phone_observe'){
       this.priorImageCounts.push(images(r.main.messages).length);this.observations++;
       return JSON.stringify({ok:true,result:{snapshot_id:`snapshot-${this.observations}`,nodes:[{text:`retained text ${this.observations}`}],images:[{data:imageData,mimeType:'image/png'}]}});
     }
     return super.call(method,json);
   }
 }
 const bridge=new ImageBridge();r=new PocketRuntime(bridge);
 const providerImageCounts:number[]=[];
 r.main.agent.streamFunction=((model:any,context:any)=>{
   providerImageCounts.push(images(context.messages).length);
   const stream=new AssistantMessageEventStream(),call=bridge.observations<2;
   const message:any={role:'assistant',content:call?[{type:'toolCall',id:`observe-${bridge.observations}`,name:'phone_observe',arguments:{include_image:true}}]:[{type:'text',text:'Observed twice; completed.'}],api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),stopReason:call?'toolUse':'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
   stream.push({type:'done',reason:message.stopReason,message});stream.end(message);return stream;
 }) as any;
 command(r,{action:'send',text:'Observe twice'});await until(()=>!r.state().active);
 assert.deepEqual(providerImageCounts,[0,1,1],'the active model context retains only the newest observation');
 assert.deepEqual(bridge.priorImageCounts,[0,0],'previous image is released before the next native capture');
 assert.equal(images(r.main.messages).length,0,'terminal response releases its last screenshot');
 const results=r.main.messages.filter(m=>m.role==='toolResult');
 assert.equal(results.length,2);assert.ok(results.every(m=>m.content.some((c:any)=>c.type==='text'&&c.text.includes('retained text'))));
 assert.ok(results.every(m=>m.content.some((c:any)=>c.type==='text'&&c.text.includes('Previous screenshot omitted'))));
 assert.ok(!JSON.stringify(bridge.state).includes(imageData));assert.equal(r.state().lastError,null);
});

test('a resolved provider error remains visible, persisted and credential-redacted after idle/restart',async()=>{
 const bridge=new Bridge();bridge.credentials.apiKey='synthetic-secret-token';
 const r=new PocketRuntime(bridge);
 r.main.agent.streamFunction=((model:any)=>{
   const stream=new AssistantMessageEventStream();
   const message:any={role:'assistant',content:[],api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),stopReason:'error',errorMessage:'Invalid credential synthetic-secret-token: provider unavailable',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
   stream.push({type:'error',reason:'error',error:message});stream.end(message);return stream;
 }) as any;
 command(r,{action:'send',text:'Check unavailable provider'});
 await until(()=>!r.state().active);
 assert.equal(r.state().lastError,'Invalid credential [redacted]: provider unavailable');
 assert.equal(r.state().messages.at(-1).stopReason,'error');
 assert.match(r.state().messages.at(-1).text,/Request failed: Invalid credential \[redacted\]/);
 assert.ok(bridge.events.some(e=>e.type==='error'&&e.error.includes('provider unavailable')));
 assert.equal(bridge.state.lastError,r.state().lastError);
 assert.ok(!JSON.stringify(bridge.state).includes('synthetic-secret-token'));
 assert.ok(!JSON.stringify(bridge.events).includes('synthetic-secret-token'));
 const restored=new PocketRuntime(bridge);await until(()=>!restored.state().active);
 assert.equal(restored.state().lastError,r.state().lastError);
 assert.equal(restored.state().messages.at(-1).stopReason,'error');
 command(restored,{action:'send',text:'hello'});await until(()=>!restored.state().active);
 assert.equal(restored.state().lastError,null,'next successful turn clears the error');
});

test('main responds under worker load; completion is persisted and wakes main once; worker direct followup retains identity',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate long task delay=300'});
 await until(()=>r.state().tasks.length===1&&!r.busy);
 const id=r.state().tasks[0].conversationId;
 assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'send',text:'hello while working'});
 await until(()=>r.state().messages.some((m:any)=>m.text.includes('Main is responsive: hello')));
 assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'steer_worker',conversationId:id,text:'Use care'});
 await until(()=>r.state().tasks[0].status==='completed'&&!r.state().active);
 assert.ok(bridge.tools.some(t=>t.owner===id&&t.name==='phone_capabilities'));
 assert.equal(r.state().mainTurns,3);
 assert.equal(bridge.state.delivered.length,1);
 assert.equal(bridge.state.workers[0].generations[0].receipts.model,true);
 assert.ok(bridge.state.workers[0].generations[0].steers.length);
 const restored=new PocketRuntime(bridge);await delay(50);
 assert.equal(restored.state().mainTurns,3);
 command(restored,{action:'send',conversationId:id,text:'Follow up delay=10'});
 await until(()=>restored.state().tasks[0].generation===2&&restored.state().tasks[0].status==='completed'&&!restored.state().active);
 assert.equal(restored.state().tasks[0].conversationId,id);
 assert.equal(restored.state().mainTurns,3,'user initiated worker followup does not wake main');
 command(restored,{action:'inspect_worker',conversationId:id});
 assert.equal(bridge.state.workers[0].generations[1].receipts.user,true);
});
test('recursive Pi9 children complete with shared concurrency cap one and suspended join slot',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge,{maxExecuting:1,maxConversations:12});
 command(r,{action:'send',text:'delegate nested fixture'});
 await until(()=>r.state().tasks.length===2&&!r.state().active);
 assert.ok(r.state().tasks.every((t:any)=>t.status==='completed'));
 const parent=r.state().tasks.find((t:any)=>!t.parentConversationId);
 assert.equal(parent.generations[0].nestedJoins[0].state,'completed');
 assert.equal(r.state().mainTurns,2);
});
test('process death preserves worker identity/transcript and marks work interrupted; resume requires receipt',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=500'});
 await until(()=>r.state().tasks[0]?.status==='running');
 const checkpoint=structuredClone(bridge.state),id=checkpoint.workers[0].conversationId;
 await r.runtime.cancelSubagent(id);
 bridge.state=checkpoint;
 const restored=new PocketRuntime(bridge);
 await until(()=>!restored.state().active);
 assert.equal(restored.state().tasks[0].status,'interrupted');
 assert.match(restored.state().tasks[0].generations[0].status.error,/unknown outcomes/);
 assert.equal(restored.state().tasks[0].conversationId,id);
 command(restored,{action:'send',conversationId:id,text:'Inspect before continuing delay=10'});
 await until(()=>restored.state().tasks[0].generation===2&&!restored.state().active);
 assert.equal(restored.state().tasks[0].status,'completed');
});
test('stop current main leaves worker running; cancel worker and stop all are distinct',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=30000'});
 await until(()=>r.state().tasks[0]?.status==='running');
 command(r,{action:'stop_main'});assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'cancel_worker',conversationId:r.state().tasks[0].conversationId});
 await until(()=>!r.state().active);
 assert.equal(r.state().tasks[0].status,'cancelled');
 command(r,{action:'send',text:'delegate fixture delay=30000'});
 await until(()=>r.state().tasks[1]?.status==='running');
 command(r,{action:'stop_all'});await until(()=>!r.state().active);
 assert.equal(r.state().tasks[1].status,'cancelled');
});
test('recursive tool grants intersect with the parent and survive durable restore',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge,{maxExecuting:1,maxConversations:12});
 const handle=r.startTasks([{kind:'spawn',agent:'researcher',label:'Read only parent',prompt:'nested task'}],{initiatedBy:'model'});
 await handle.completion;await until(()=>!r.state().active);
 const child=r.state().tasks.find((t:any)=>t.parentConversationId);
 assert.ok(child);assert.ok(child.requestedConfig.tools.includes('phone_capabilities'));
 assert.ok(!child.requestedConfig.tools.includes('phone_act'));
 assert.ok(!child.effectiveConfig.tools.includes('phone_act'));
 const restored=new PocketRuntime(bridge);
 const reloaded=restored.state().tasks.find((t:any)=>t.conversationId===child.conversationId);
 assert.ok(!reloaded.requestedConfig.tools.includes('phone_act'));
 assert.ok(!restored.sessions.get(child.conversationId)!.agent.state.tools.some((x:any)=>x.name==='phone_act'));
});
test('scripted fixture executes the real typed tool loop and verifies exact postcondition',async()=>{
 class FixtureBridge extends Bridge {
   count=0;typed='';
   override call(method:string,json:string):string {
     const a=JSON.parse(json);
     if(method==='tool'){
       if(a.name==='phone_act'){if(a.args.kind==='type')this.typed=a.args.text;else if(a.args.kind==='click')this.count++;}
       if(a.name==='phone_observe')return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`s${this.tools.length}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'} ]}});
     }
     return super.call(method,json);
   }
 }
 const bridge=new FixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.count,1);assert.equal(bridge.typed,'PocketWorks');
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
 assert.ok(bridge.tools.some(x=>x.name==='phone_batch'));
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act').length,2);
});

test('scripted fixture retries cold-launch NOT_READY reads without replaying mutations',async()=>{
 class ColdFixtureBridge extends Bridge {
   count=0;typed='';observes=0;
   override call(method:string,json:string):string {
     const a=JSON.parse(json);
     if(method==='tool') {
       if(a.name==='phone_act'){if(a.args.kind==='type')this.typed=a.args.text;else if(a.args.kind==='click')this.count++;}
       if(a.name==='phone_observe') {
         this.observes++;
         this.tools.push(a);
         if(this.observes<=2)return JSON.stringify({ok:true,result:{isError:true,code:'NOT_READY',message:'Foreground accessibility root is transitioning',retry_safe:false}});
         return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`cold-${this.observes}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'} ]}});
       }
     }
     return super.call(method,json);
   }
 }
 const bridge=new ColdFixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.observes,5,'two transitional reads plus three workflow observations');
 assert.equal(bridge.tools.filter(x=>x.name==='phone_open_app').length,1);
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act'&&x.args.kind==='type').length,1);
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act'&&x.args.kind==='click').length,1);
 assert.equal(bridge.count,1);assert.equal(bridge.typed,'PocketWorks');
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
});

test('fixture replans only confirmed pre-dispatch stale actions with fresh observations and IDs',async()=>{
 class ChangingFixtureBridge extends Bridge {
  count=0;typed='';rejected=new Set<string>();ids=new Set<string>();observes=0;
  override call(method:string,json:string):string {
   const a=JSON.parse(json);
   if(method==='tool') {
    if(a.name==='phone_act') {
     assert.ok(!this.ids.has(a.args.operation_id));this.ids.add(a.args.operation_id);
     this.tools.push(a);
     if(!this.rejected.has(a.args.kind)) {
      this.rejected.add(a.args.kind);
      return JSON.stringify({ok:true,result:{isError:true,code:'STALE_OBSERVATION',dispatch_status:'not_dispatched',retry_safe:false}});
     }
     assert.equal(a.args.snapshot_id,`changing-${this.observes}`);
     if(a.args.kind==='type')this.typed=a.args.text;else this.count++;
     return JSON.stringify({ok:true,result:{status:'dispatched'}});
    }
    if(a.name==='phone_observe') {
     this.observes++;this.tools.push(a);
     return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`changing-${this.observes}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'}]}});
    }
   }
   return super.call(method,json);
  }
 }
 const bridge=new ChangingFixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.count,1);assert.equal(bridge.ids.size,4);assert.equal(bridge.observes,5);
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
});

test('fixture never retries unknown mutations or stale errors without a pre-dispatch receipt',async()=>{
 for(const code of ['RESULT_UNKNOWN','STALE_OBSERVATION']) {
  class UnknownFixtureBridge extends Bridge {
   override call(method:string,json:string):string {
    const a=JSON.parse(json);
    if(method==='tool'&&a.name==='phone_act') {this.tools.push(a);return JSON.stringify({ok:true,result:{isError:true,code,retry_safe:false}});}
    if(method==='tool'&&a.name==='phone_observe')return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:'unknown-fixture',nodes:[{description:'AGENT_INPUT'}]}});
    return super.call(method,json);
   }
  }
  const bridge=new UnknownFixtureBridge(),r=new PocketRuntime(bridge);
  command(r,{action:'send',text:'delegate fixture delay=10'});
  await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
  assert.equal(bridge.tools.filter(x=>x.name==='phone_act').length,1);
  assert.match(r.state().tasks[0].output,/Fixture failed/);
 }
});

test('custom main system prompt persists through turns, restart and provider reconfiguration',async()=>{
 const bridge=new Bridge(),runtime=new PocketRuntime(bridge);
 const custom='You are the custom main Pocket Agent. Keep replies concise and verified.';
 command(runtime,{action:'set_system_prompt',text:custom});
 assert.equal(runtime.state().mainSystemPrompt,custom);
 assert.match(runtime.main.agent.state.systemPrompt,/custom main Pocket Agent/);
 command(runtime,{action:'send',text:'hello custom prompt'});
 await until(()=>!runtime.state().active);
 assert.match(String((runtime.main.messages[0] as any).content),/custom main Pocket Agent/,'memory injection must preserve the custom main prompt');
 bridge.credentials={authMode:'scripted',model:'scripted'};
 command(runtime,{action:'configure'});
 assert.equal(runtime.state().mainSystemPrompt,custom);
 assert.match(runtime.main.agent.state.systemPrompt,/custom main Pocket Agent/,'provider rebuild must reuse the custom prompt');
 const restored=new PocketRuntime(bridge);
 assert.equal(restored.state().mainSystemPrompt,custom,'durable state restores the custom prompt');
 assert.match(restored.main.agent.state.systemPrompt,/custom main Pocket Agent/);
 command(restored,{action:'set_system_prompt',reset:true});
 assert.equal(restored.state().mainSystemPrompt,DEFAULT_MAIN_PROMPT);
 assert.equal(bridge.state.mainSystemPrompt,DEFAULT_MAIN_PROMPT,'reset persists the built-in default');
});

test('paused main with retained inbox can still apply provider and prompt settings',()=>{
 const bridge=new Bridge(),runtime=new PocketRuntime(bridge),internal=runtime as any;
 internal.mainPaused=true;internal.inbox=[{type:'user',text:'retained while context is full'}];
 command(runtime,{action:'configure',systemPrompt:'Prompt updated while paused'});
 assert.equal(runtime.state().mainSystemPrompt,'Prompt updated while paused');
 assert.equal(runtime.state().mainPaused,true);assert.equal(internal.inbox.length,1,'settings must not discard paused queued work');
});

test('provider reasoning follows protected settings across runtime reloads',()=>{
 const bridge=new Bridge();
 bridge.credentials={authMode:'openai',endpoint:'https://example.test/v1',model:'gpt-6-luna',apiKey:'synthetic-key',reasoning:'high'};
 const runtime=new PocketRuntime(bridge);
 assert.equal(runtime.state().provider.reasoning,'high');
 bridge.credentials={...bridge.credentials,reasoning:'xhigh'};
 command(runtime,{action:'configure'});
 assert.equal(runtime.state().provider.reasoning,'xhigh');
 const restored=new PocketRuntime(bridge);
 assert.equal(restored.state().provider.reasoning,'xhigh');
});

test('new main thread clears old main delivery but retains a resumable worker',async()=>{
 const bridge=new Bridge(),runtime=new PocketRuntime(bridge),internal=runtime as any;
 command(runtime,{action:'send',text:'delegate fixture delay=80'});
 await until(()=>runtime.state().tasks[0]?.status==='running'&&!runtime.busy,4000);
 const id=runtime.state().tasks[0].conversationId;
 internal.mainPaused=true;
 await until(()=>runtime.state().tasks[0]?.status==='completed',8000);
 assert.ok(internal.inbox.some((item:any)=>item.type==='completion'),'worker completion should be pending for the old main thread');
 assert.equal(runtime.runtime.conversation(id).generations.at(-1)!.receipts.model,false);
 const workerMessages=structuredClone(runtime.state().workerMessages);
 internal.inbox.push({type:'user',text:'stale queued user turn'});
 command(runtime,{action:'new_main'});
 assert.deepEqual(runtime.state().messages,[]);assert.equal(runtime.state().mainTurns,0);assert.deepEqual(internal.inbox,[]);
 assert.equal(runtime.state().tasks[0].conversationId,id,'retained worker identity must survive a main-thread reset');
 assert.deepEqual(runtime.state().workerMessages,workerMessages,'worker transcript must survive a main-thread reset');
 assert.equal(runtime.runtime.conversation(id).generations.at(-1)!.receipts.model,true,'suppressed model completion must be acknowledged');
 command(runtime,{action:'send',conversationId:id,text:'Follow up delay=10'});
 await until(()=>runtime.state().tasks[0].generation===2&&runtime.state().tasks[0].status==='completed',8000);
 assert.equal(runtime.state().tasks[0].conversationId,id,'worker must remain resumable after the main reset');
 assert.equal(bridge.state.mainMessages.filter((m:any)=>m.role==='user'||m.role==='assistant').length,0,'durable main transcript remains fresh after direct worker follow-up');
 internal.busy=true;
 const rejected=JSON.parse(runtime.dispatch(JSON.stringify({action:'new_main'})));
 internal.busy=false;
 assert.equal(rejected.ok,false);assert.match(rejected.error,/Stop active work/);
});

test('fresh-main startup intent discards persisted main work before automatic resume',()=>{
 const bridge=new Bridge();
 bridge.state={freshMainRequested:true,mainSystemPrompt:'Persisted custom prompt',mainMessages:[{role:'user',content:[{type:'text',text:'old main request'}],timestamp:1}],inbox:[{type:'user',text:'old queued request'}],mainTurns:9,mainPaused:true,busy:true,lastError:'old failure',observationPending:['old observation']};
 const runtime=new PocketRuntime(bridge),state=runtime.state();
 assert.deepEqual(state.messages,[]);assert.equal(state.mainTurns,0);assert.equal(state.mainPaused,false);assert.equal(state.mainInterrupted,false);assert.equal(state.lastError,null);
 assert.equal(state.mainSystemPrompt,'Persisted custom prompt');assert.deepEqual((runtime as any).inbox,[]);assert.deepEqual((runtime as any).observationPending,[]);
 assert.equal(bridge.state.freshMainRequested,undefined,'startup intent is consumed by the first durable checkpoint');
 assert.deepEqual(bridge.state.mainMessages.filter((m:any)=>m.role==='user'||m.role==='assistant'),[]);
});

test('account credentials stay redacted from durable state and events after provider changes',async()=>{
 const bridge=new Bridge();
 Object.assign(bridge.credentials,{accessToken:'synthetic-access-token',refreshToken:'synthetic-refresh-token',idToken:'synthetic-id-token',accountId:'synthetic-account-id'});
 const r=new PocketRuntime(bridge);
 const secrets=['synthetic-access-token','synthetic-refresh-token','synthetic-id-token','synthetic-account-id'];
 r.reportError(new Error(secrets.join(' ')));
 r.main.agent.state.messages.push({role:'assistant',content:[{type:'text',text:secrets.join(' ')}],timestamp:Date.now()} as any);
 r.save();r.publish();
 for(const secret of secrets){assert.ok(!JSON.stringify(bridge.state).includes(secret));assert.ok(!JSON.stringify(bridge.events).includes(secret));}
 bridge.credentials={authMode:'scripted'};
 command(r,{action:'configure'});
 r.reportError(new Error(secrets.join(' ')));r.save();r.publish();
 for(const secret of secrets){assert.ok(!JSON.stringify(bridge.state).includes(secret));assert.ok(!JSON.stringify(bridge.events).includes(secret));}
 await until(()=>!r.state().active);
});


test('voice-scoped interruption aborts only its matching turn and removes stale queued speech',()=>{
 const runtime=new PocketRuntime(new Bridge());
 const old='9ac6c359-d522-4fa3-a00b-a7efdf0c9571';
 const newer='a4d7ef33-3b44-4d3e-8caf-3591ae60a23b';
 const anyRuntime=runtime as any;
 let aborts=0;
 runtime.main.agent.abort=()=>{aborts++;};
 anyRuntime.speakingTurn=newer;
 command(runtime,{action:'stop_main',voiceSessionId:old});
 assert.equal(aborts,0,'late stop must not abort the next main response');
 command(runtime,{action:'stop_main',voiceSessionId:newer});
 assert.equal(aborts,1,'matching stop cancels its own main reply');
 const rejected=JSON.parse(runtime.dispatch(JSON.stringify({action:'stop_main',voiceSessionId:'invalid'})));
 assert.equal(rejected.ok,false,'malformed scoped abort must not fall back to global cancellation');
 assert.equal(aborts,1);
 anyRuntime.speakingTurn=null;
 anyRuntime.pumping=true; // Freeze the inbox while verifying queued-turn cancellation.
 command(runtime,{action:'send',text:'queued old',voiceSessionId:old});
 command(runtime,{action:'send',text:'queued new',voiceSessionId:newer});
 assert.deepEqual(anyRuntime.inbox.map((item:any)=>item.voiceSessionId),[old,newer]);
 command(runtime,{action:'stop_main',voiceSessionId:old});
 assert.deepEqual(anyRuntime.inbox.map((item:any)=>item.voiceSessionId),[newer],
   'interrupted but not-yet-started requests must never speak later');
});

test('voice input streams only its own main reply; ordinary turns and worker completions remain silent',async()=>{
 const bridge=new Bridge(),runtime=new PocketRuntime(bridge);
 const id='9ac6c359-d522-4fa3-a00b-a7efdf0c9571';
 command(runtime,{action:'send',text:'hello voice',voiceSessionId:id});
 await until(()=>!runtime.state().active);
 const own=bridge.events.filter(e=>e.sessionId===id);
 assert.ok(own.some(e=>e.type==='voice_delta'&&e.text.includes('Main is responsive: hello voice')));
 assert.equal(own.filter(e=>e.type==='voice_end').length,1);
 bridge.events.length=0;
 command(runtime,{action:'send',text:'hello text'});
 await until(()=>!runtime.state().active);
 assert.ok(!bridge.events.some(e=>e.type.startsWith('voice_')));
 bridge.events.length=0;
 command(runtime,{action:'send',text:'delegate long task delay=100',voiceSessionId:id});
 await until(()=>runtime.state().tasks.length===1&&!runtime.state().active);
 await until(()=>runtime.state().tasks[0]?.status==='completed'&&!runtime.state().active,8000);
 assert.equal(bridge.events.filter(e=>e.type==='voice_end').length,1,'worker completion cannot trigger another spoken turn');
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').length,0,'worker completion without explicit speech tool stays silent');
 bridge.events.length=0;
 command(runtime,{action:'send',text:'hello bad id',voiceSessionId:'not-a-session-id'});
 await until(()=>!runtime.state().active);
 assert.ok(!bridge.events.some(e=>e.type.startsWith('voice_')),'reject unscoped voice output');
});

test('registered speak_to_user tool requests optional speech without asserting delivery or speaking after worker completion',async()=>{
 const bridge=new Bridge(), runtime=new PocketRuntime(bridge);
 const main=runtime.main.agent.state.tools.find((tool:any)=>tool.name==='speak_to_user');
 assert.ok(main,'main has optional spoken output');
 const worker=(runtime as any).createSession('worker-test',{definition:runtime.registry.agents.get('phone-operator'),requestedConfig:{tools:['speak_to_user']}});
 const tool=worker.agent.state.tools.find((tool:any)=>tool.name==='speak_to_user');
 assert.ok(tool,'registered worker can initiate speech');
 assert.equal(runtime.registry.agents.get('phone-operator')!.tools!.includes('speak_to_user'),true);
 assert.equal(runtime.registry.agents.get('researcher')!.tools!.includes('speak_to_user'),true);
 assert.equal(validateToolArguments(tool,{type:'toolCall',id:'speak1',name:'speak_to_user',arguments:{message:'Hello',listen_after:false}}).listen_after,false);
 const out=await tool.execute('speak1',{message:' Reminder at six. ',listen_after:false});
 assert.equal(out.details.status,'requested');
 assert.match(out.details.note,/does not mean accepted or heard/);
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).type,'voice_announce');
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).message,'Reminder at six.');
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).listenAfter,false);
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).owner,'worker-test');
 assert.match(bridge.events.filter(e=>e.type==='voice_announce').at(-1).id,/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
 await main.execute('speak2',{message:'Please reply'});
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).owner,'main');
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').at(-1).listenAfter,true);
 await assert.rejects(tool.execute('speak3',{message:'  '}),/1..800/);
 const controller=new AbortController();controller.abort();
 await assert.rejects(tool.execute('speak4',{message:'No',listen_after:true},controller.signal),/cancelled/);
 assert.equal(bridge.events.filter(e=>e.type==='voice_announce').length,2);
});

test('a discovered worker model is pinned across execution and restore without changing the selected main model',async()=>{
 const originalFetch=globalThis.fetch;
 const wireModels:string[]=[];
 const bridge=new Bridge();
 bridge.credentials={authMode:'openai',endpoint:'https://api.example.test/v1',model:'main-strong',apiKey:'synthetic-api-key',providerRevision:'provider-a'};
 globalThis.fetch=(async(input:any,init?:RequestInit)=>{
   if(String(input).endsWith('/models'))return Response.json({data:[{id:'worker/fast'},{id:'worker/strong'}]});
   const request=JSON.parse(String(init?.body));wireModels.push(request.model);
   const event={type:'response.completed',response:{id:'resp_worker',status:'completed',output:[],usage:{input_tokens:1,output_tokens:0,total_tokens:1,input_tokens_details:{cached_tokens:0}}}};
   return new Response('event: response.completed\ndata: '+JSON.stringify(event)+'\n\n',{headers:{'content-type':'text/event-stream'}});
 }) as typeof fetch;
 try {
   const runtime=new PocketRuntime(bridge);
   assert.equal(runtime.main.agent.state.model.id,'main-strong');
   const rejected=runtime.startTasks([{kind:'spawn',agent:'researcher',label:'Unknown model',prompt:'Read status',model:'other/unknown'}],{initiatedBy:'user'});
   assert.equal(rejected.starts[0].ok,false);
   command(runtime,{action:'list_models',requestId:'catalog_123'});
   await until(()=>bridge.events.some(e=>e.type==='models'&&e.requestId==='catalog_123'));
   assert.deepEqual(bridge.events.find(e=>e.type==='models')?.models.map((m:any)=>m.id),['worker/fast','worker/strong']);
   const handle=runtime.startTasks([{kind:'spawn',agent:'researcher',label:'Cheap status',prompt:'Read status',model:'worker/fast'}],{initiatedBy:'user'});
   assert.equal(handle.starts[0].ok,true);
   await handle.completion;
   const task=runtime.state().tasks[0];
   assert.equal(task.effectiveConfig.model,'worker/fast');
   assert.equal(runtime.sessions.get(task.conversationId)?.agent.state.model.id,'worker/fast');
   assert.equal(runtime.main.agent.state.model.id,'main-strong');
   assert.deepEqual(wireModels,['worker/fast']);
   const stateWithPinnedWorker=structuredClone(bridge.state);
   const restored=new PocketRuntime(bridge);
   assert.equal(restored.main.agent.state.model.id,'main-strong');
   assert.equal(restored.sessions.get(task.conversationId)?.agent.state.model.id,'worker/fast');
   const stoppedBridge=new Bridge();
   stoppedBridge.state=structuredClone(stateWithPinnedWorker);
   stoppedBridge.credentials={...bridge.credentials,apiKey:'rotated-api-key',providerRevision:'provider-b'};
   const restartedAfterCredentialChange=new PocketRuntime(stoppedBridge);
   assert.equal(restartedAfterCredentialChange.sessions.get(task.conversationId)?.agent.state.model.id,'main-strong','restart under a new credential drops the old worker model');
   assert.equal(restartedAfterCredentialChange.state().tasks[0].requestedConfig.model,undefined,'restart clears the stale requested model');
   assert.equal(stoppedBridge.state.workers[0].requestedConfig.model,undefined,'restart persists the cleared model state');
   bridge.credentials={...bridge.credentials,apiKey:'rotated-api-key',providerRevision:'provider-b'};
   command(restored,{action:'configure'});
   assert.equal(restored.sessions.get(task.conversationId)?.agent.state.model.id,'main-strong','live credential changes drop the old worker model');
   assert.equal(restored.state().tasks[0].requestedConfig.model,undefined,'live worker state clears the stale model');
   assert.equal(bridge.state.workers[0].requestedConfig.model,undefined,'saved worker state cannot restore the old provider model');
 } finally {globalThis.fetch=originalFetch;}
});
