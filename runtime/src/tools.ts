import { Type } from 'typebox';
import { randomUUID } from 'node:crypto';
import schemas from '../../app/src/main/assets/tools.json';
import { SubagentParams, parseSubagentInvocation } from '../vendor/pi9/schema.js';
import { REASONING_EFFORTS } from './provider.js';

// Keep upstream parsing/contracts intact; advertise only mobile-supported spawn
// inputs. Agent selection still determines the recursively inherited tool grant.
const mobileSpawn:any={...SubagentParams.properties.spawns.items,properties:{...SubagentParams.properties.spawns.items.properties}};
const unsupportedSpawnOverrides=['cwd','skills','thinking'];
for(const field of unsupportedSpawnOverrides)delete mobileSpawn.properties[field];
if(mobileSpawn.required)mobileSpawn.required=mobileSpawn.required.filter((field:string)=>!unsupportedSpawnOverrides.includes(field));
mobileSpawn.properties.reasoning=Type.Optional(Type.Union(REASONING_EFFORTS.map(level=>Type.Literal(level)) as any,{description:'Reasoning effort for this worker. Omit to inherit the caller reasoning effort.'}));
export const MobileSubagentParams=Type.Unsafe({...SubagentParams,properties:{...SubagentParams.properties,spawns:{...SubagentParams.properties.spawns,items:mobileSpawn}}});

function normalizeMobileSubagentInvocation(params:any):any {
  if(params?.action!=='spawn'||!Array.isArray(params.spawns))return params;
  return {...params,spawns:params.spawns.map((task:any)=>{
    if(!task||typeof task!=='object'||Array.isArray(task)||task.reasoning===undefined)return task;
    const {reasoning,...rest}=task;
    return {...rest,thinking:reasoning};
  })};
}

export function toolResult(data:any):any {
  let latestImage:any;
  const withoutImages=(value:any):any=>{
    if(Array.isArray(value))return value.map(withoutImages);
    if(!value||typeof value!=='object')return value;
    const result:any={};
    for(const [key,child] of Object.entries(value)) {
      if(key==='images') {
        // Native batches nest observations under results[].result. Earlier
        // screenshots describe stale screen state; never fall back to one if
        // the latest capture exceeds the independent image payload bound.
        if(Array.isArray(child))for(const image of child) {
          latestImage=undefined;
          if(typeof image?.data==='string'&&image.data.length>0&&image.data.length<=2000000&&typeof image.mimeType==='string')latestImage={type:'image',data:image.data,mimeType:image.mimeType};
        }
      } else result[key]=withoutImages(child);
    }
    return result;
  };
  const text=withoutImages(data);
  const json=JSON.stringify(text??null);
  const content:any[]=[{type:'text',text:json.length>32000?JSON.stringify({isError:true,code:'OUTPUT_LIMIT',message:'Native output exceeded 32 KB; narrow the query.'}):json}];
  if(latestImage)content.push(latestImage);
  return {content,details:json.length>32000?{isError:true,code:'OUTPUT_LIMIT'}:text,isError:Boolean(data?.isError)||json.length>32000};
}
type ObservationState={packageName:string,windowId:number,signature:string};
type UiTransition={startedAt:number,deadline:number,expectedPackage?:string,previous?:ObservationState};
type OwnerUiState={last?:ObservationState,transition?:UiTransition};
const uiStates=new WeakMap<object,Map<string,OwnerUiState>>();
type DisplayTarget='virtual'|'physical';
function displayTarget(args:any):DisplayTarget {return args?.display_target==='physical'?'physical':'virtual';}
function uiStateKey(owner:string,target:DisplayTarget):string{return owner+'\u0000'+target;}

function ownerUiState(host:any,owner:string,target:DisplayTarget='virtual'):OwnerUiState {
  let owners=uiStates.get(host);
  if(!owners){owners=new Map();uiStates.set(host,owners);}
  const key=uiStateKey(owner,target);
  let state=owners.get(key);
  if(!state){state={};owners.set(key,state);}
  return state;
}
export function clearNativeToolUiState(host:any,owner:string,target?:DisplayTarget):void {
  const owners=uiStates.get(host);if(!owners)return;
  if(target){owners.delete(uiStateKey(owner,target));return;}
  owners.delete(uiStateKey(owner,'virtual'));owners.delete(uiStateKey(owner,'physical'));
}
function observationState(result:any):ObservationState {
  const nodes=Array.isArray(result?.nodes)?result.nodes.slice(0,40).map((n:any)=>[n?.resource_id,n?.text,n?.description,n?.role,n?.bounds]):[];
  return {packageName:String(result?.package_name??''),windowId:Number(result?.window_id??-1),signature:JSON.stringify(nodes)};
}
function transitionRetryable(result:any):boolean {
  if(!result?.isError)return false;
  if(result.code==='STALE_OBSERVATION')return true;
  return result.code==='NOT_READY'&&/active accessibility window|screen transition|virtual display|shizuku backend|display service/i.test(String(result.message??''));
}
function backendLeaseLost(result:any):boolean {
  return result?.isError&&result.code==='NOT_READY'&&/shizuku backend|display service disconnected|virtual display backend disconnected|no virtual display/i.test(String(result.message??''));
}
function observationBackendTimedOut(result:any):boolean {
  return result?.isError&&result.code==='RESULT_UNKNOWN'&&/timed out/i.test(String(result.message??''));
}
async function pauseForUi(ms:number,signal?:AbortSignal):Promise<void> {
  if(signal?.aborted)throw new Error('Display request cancelled');
  await new Promise<void>((resolve,reject)=>{
    const timer=setTimeout(done,ms);
    const abort=()=>{clearTimeout(timer);signal?.removeEventListener('abort',abort);reject(new Error('Display request cancelled'));};
    function done(){signal?.removeEventListener('abort',abort);resolve();}
    signal?.addEventListener('abort',abort,{once:true});
  });
}
function markUiTransition(host:any,owner:string,args:any,expectedPackage?:string):void {
  const state=ownerUiState(host,owner,displayTarget(args)),now=Date.now();
  state.transition={startedAt:now,deadline:now+2500,expectedPackage,previous:state.last};
}
async function observeReady(host:any,owner:string,args:any,signal?:AbortSignal):Promise<any> {
  const target=displayTarget(args);
  let state=ownerUiState(host,owner,target);
  let readinessDeadline=Date.now()+2000;
  let recoveredLease=false,last:any;
  for(;;) {
    if(signal?.aborted)throw new Error('Display request cancelled');
    last=host.call('tool',{name:'phone_observe',args,owner});
    if(target==='virtual'&&(backendLeaseLost(last)||observationBackendTimedOut(last))&&!recoveredLease&&host.ensureDisplay) {
      host.releaseScreen?.(owner);clearNativeToolUiState(host,owner,'virtual');
      await pauseForUi(100,signal);
      const acquired=await host.ensureDisplay(owner,signal);
      if(acquired?.isError)return acquired;
      recoveredLease=true;state=ownerUiState(host,owner,'virtual');
      readinessDeadline=Date.now()+2000;
      continue;
    }
    const now=Date.now(),transition=state.transition;
    if(transitionRetryable(last)&&now<(transition?.deadline??readinessDeadline)) {
      await pauseForUi(120,signal);
      continue;
    }
    if(recoveredLease)last={...last,display_replaced:true,display_note:'Display lease was replaced after a backend failure; prior app state on this private display was discarded.'};
    if(last?.isError)return last;
    const current=observationState(last);
    if(transition) {
      if(transition.expectedPackage&&current.packageName!==transition.expectedPackage) {
        if(now<transition.deadline){await pauseForUi(120,signal);continue;}
        state.transition=undefined;
        return {isError:true,code:'NOT_READY',message:`Launch transition to ${transition.expectedPackage} was not observable before the readiness deadline; last observed package was ${current.packageName||'unknown'}. Observe again before acting.`};
      }
      if(!transition.expectedPackage) {
        const changed=!transition.previous||current.packageName!==transition.previous.packageName||current.windowId!==transition.previous.windowId||current.signature!==transition.previous.signature;
        const elapsed=now-transition.startedAt;
        if(!changed&&elapsed<1200&&now<transition.deadline){await pauseForUi(120,signal);continue;}
        if(!transition.previous&&elapsed<350&&now<transition.deadline){await pauseForUi(120,signal);continue;}
      }
      state.transition=undefined;
    }
    state.last=current;
    return last;
  }
}
const UI_TOOL_NAMES=new Set(['phone_observe','phone_act','phone_open_app','phone_open_url','phone_clock_screen','phone_settings']);
function firstBatchUiCall(args:any):any {
  return Array.isArray(args?.calls)?args.calls.find((call:any)=>UI_TOOL_NAMES.has(call?.name)):undefined;
}
async function settlePendingBatchObservation(host:any,owner:string,args:any,signal?:AbortSignal):Promise<any> {
  const firstUi=firstBatchUiCall(args);
  if(!firstUi)return null;
  const target=displayTarget(firstUi.args),state=ownerUiState(host,owner,target);
  if(!state.transition||!['phone_observe','phone_act'].includes(firstUi?.name))return null;
  const observeArgs=firstUi?.name==='phone_observe'?{...(firstUi.args??{}),include_image:false}:{include_image:false,display_target:target};
  return observeReady(host,owner,observeArgs,signal);
}
function recordBatchUiState(host:any,owner:string,args:any,result:any):void {
  const calls=Array.isArray(args?.calls)?args.calls:[],entries=Array.isArray(result?.results)?result.results:[];
  for(const entry of entries) {
    const call=calls[Number(entry?.index)],nested=entry?.result;
    if(nested?.display_replaced)clearNativeToolUiState(host,owner,'virtual');
    if(!call||nested?.isError)break;
    if(call.name==='phone_open_app')markUiTransition(host,owner,call.args,call.args?.package_name);
    else if(call.name==='phone_open_url'||call.name==='phone_clock_screen'||call.name==='phone_settings')markUiTransition(host,owner,call.args);
    else if(call.name==='phone_observe') {
      const state=ownerUiState(host,owner,displayTarget(call.args));
      state.transition=undefined;state.last=observationState(nested);
    }
  }
}

export function nativeTools(host:any,owner:string,grant?:readonly string[]):any[] {
  return schemas.filter(s=>!grant || grant.includes(s.name)).map(s=>({
    name:s.name,label:s.name,description:s.description,
    parameters:Type.Unsafe(s.inputSchema),
    execute:async(_id:string,args:any,signal?:AbortSignal)=>{
      if(signal?.aborted) throw new Error('Cancelled before native dispatch');
      const authorize=()=>{
        host.authorizePhone?.(owner,s.name,args);
        if(s.name==='phone_batch')for(const call of args.calls??[])host.authorizePhone?.(owner,call.name,call.args);
      };
      authorize();
      // Batch entries must also stay inside this conversation's grant.
      if(s.name==='phone_batch' && args.calls?.some((x:any)=>!schemas.some(t=>t.name===x.name) || (grant && !grant.includes(x.name)))) throw new Error('Batch tool exceeds tool grant');
      if(s.name==='phone_screen'){
        if(args.action==='release'){
          const result=host.releaseScreen?.(owner);clearNativeToolUiState(host,owner);
          if(result?.isError)return toolResult(result);
          return toolResult({status:'released'});
        }
        if(args.action==='acquire'&&host.ensureDisplay)return toolResult(await host.ensureDisplay(owner,signal));
      }
      const targets=s.name==='phone_batch'?args.calls?.filter((c:any)=>UI_TOOL_NAMES.has(c.name)).map((c:any)=>displayTarget(c.args)):undefined;
      if(targets?.some((target:DisplayTarget)=>target!==targets[0]))return toolResult({isError:true,code:'INVALID_ARGUMENT',message:'All UI calls in one batch must use the same display_target'});
      const needsVirtualUi=(UI_TOOL_NAMES.has(s.name)&&displayTarget(args)==='virtual')||
        (s.name==='phone_batch'&&args.calls?.some((c:any)=>UI_TOOL_NAMES.has(c.name)&&displayTarget(c.args)==='virtual'));
      if(needsVirtualUi&&host.ensureDisplay){
        const result=await host.ensureDisplay(owner,signal);
        if(result?.isError)return toolResult(result);
      }
      if(signal?.aborted)throw new Error('Cancelled before native dispatch');
      if(s.name==='phone_observe'||(s.name==='phone_batch'&&args.calls?.some((x:any)=>x.name==='phone_observe')))host.dropPriorImages(owner);
      if(s.name==='phone_batch') {
        const settled=await settlePendingBatchObservation(host,owner,args,signal);
        if(settled?.isError)return toolResult(settled);
        if(settled?.display_replaced)return toolResult({isError:true,code:'NOT_READY',message:'Display lease was replaced while settling pending UI readiness; prior private-display app state was discarded and the batch was not dispatched.',display_replaced:true,display_note:settled.display_note,batch_dispatched:false});
      }
      // Display leases can wait behind other workers. Recheck pause/approval expiry
      // after those awaits, immediately before any phone side effect.
      authorize();
      const result=s.name==='phone_observe'?await observeReady(host,owner,args,signal):host.call('tool',{name:s.name,args,owner});
      if(s.name==='phone_batch')recordBatchUiState(host,owner,args,result);
      else if(!result?.isError) {
        if(s.name==='phone_open_app')markUiTransition(host,owner,args,args.package_name);
        else if(s.name==='phone_open_url'||s.name==='phone_clock_screen'||s.name==='phone_settings')markUiTransition(host,owner,args);
      }
      if(needsVirtualUi&&result?.isError&&result.code==='NOT_READY'&&
        /virtual display|no virtual display|shizuku backend|display service/i.test(String(result.message??''))) {
        host.releaseScreen?.(owner);clearNativeToolUiState(host,owner,'virtual');
      }
      return toolResult(result);
    },
  }));
}
export function speakToUserTool(host:any,owner:string):any {
  return {
    name:'speak_to_user',label:'Speak to user',
    description:'Optional spoken announcement on this device. Call only when audio is useful or explicitly requested, not automatically for every completed task. This queues delivery without proving playback. When listen_after is true, offer a three-second silence window for a spoken reply. Never speak secrets, one-time codes, or sensitive screen content aloud.',
    parameters:Type.Object({
      message:Type.String({minLength:1,maxLength:800,description:'Short text to say aloud.'}),
      listen_after:Type.Optional(Type.Boolean({description:'Open microphone for a spoken response after the message; default true.'})),
    },{additionalProperties:false}),
    execute:async(_id:string,args:{message:string,listen_after?:boolean},signal?:AbortSignal)=>{
      if(signal?.aborted)throw new Error('Announcement cancelled before dispatch');
      const message=args.message.trim();
      if(!message||message.length>800)throw new Error('Spoken message must be 1..800 characters');
      const id=randomUUID();
      host.emit({type:'voice_announce',id,owner,message,listenAfter:args.listen_after!==false});
      return toolResult({id,status:'requested',note:'Android will attempt to enqueue and play this speech asynchronously. This request may be rejected by Android or a full queue; requested does not mean accepted or heard.'});
    },
  };
}

export function subagentTool(app:any,parent?:any):any {
  const owner=()=>parent?{conversation:parent,generation:parent.requireCurrentGeneration()}:undefined;
  return {name:'subagent',label:'Subagent',description:'Use action=models to discover provider models and their supportedReasoningLevels. Specify a listed model in spawns[].model and an optional spawns[].reasoning for an independent worker. Omitting model inherits the main model; omitting reasoning inherits the caller reasoning effort. Delegate asynchronously. spawn returns immediately. End your turn and remain available; host delivers completion. inspect does not collect. join collects; use only for explicit dependencies. resume requires the initiator collection receipt. Tools are inherited by children.',parameters:MobileSubagentParams,
    execute:async(id:string,params:any,signal?:AbortSignal)=>{
      const invocation=parseSubagentInvocation(normalizeMobileSubagentInvocation(params),{maxTasks:4});
      if('error' in invocation) return toolResult({action:params.action,error:invocation.error});
      const r=app.runtime,caller=owner(), action=invocation.action;
      let results:any[]=[];
      switch(action) {
        case 'models': {
          const all=await app.refreshModels();
          const matches=invocation.query?all.filter((model:any)=>(model.id+' '+model.name).toLowerCase().includes(invocation.query!.toLowerCase())):all;
          const offset=invocation.offset??0,limit=invocation.limit??100;
          results=[{total:matches.length,offset,models:matches.slice(offset,offset+limit),nextOffset:offset+limit<matches.length?offset+limit:null}];
          break;
        }
        case 'agents': results=[...app.registry.agents.values()].map((x:any)=>({name:x.name,description:x.description,tools:x.tools}));break;
        case 'list': results=r.queryConversations(parent?.conversationId).filter((x:any)=>{
          const g=x.generations.at(-1),status=r.subagentStatus(x.conversationId);
          return (!invocation.statuses||invocation.statuses.includes(status)) && (invocation.collected===undefined||g.receipts.model===invocation.collected);
        }).map((x:any)=>r.projectSubagent(x.conversationId,caller));break;
        case 'spawn': case 'resume': {
          const tasks=action==='spawn'?invocation.spawns:invocation.resumes;
          if(parent && r.conversationDepth(parent.conversationId)>=4) throw new Error('Maximum recursive mobile worker depth is 4');
          for(const task of tasks) {
            if('error' in task) {results.push({ok:false,...task});continue;}
            const effectiveTask=action==='spawn'&&task.thinking===undefined?{...task,thinking:parent?.requestedConfig?.thinking??app.state().provider.reasoningEffort}:task;
            const h=app.startTasks([effectiveTask],{caller,initiatedBy:'model'});
            results.push(...h.starts);void h.completion.catch(app.reportError);
          }
          break;
        }
        case 'steer': for(const x of invocation.messages) {try {results.push('error'in x?{ok:false,...x}:{ok:true,...await app.steer(x.subagentId,x.message,caller)});}catch(e){results.push({ok:false,error:String(e)});}}break;
        case 'cancel': case 'inspect': case 'remove': {
          for(const target of invocation.subagentIds) {
            if(typeof target!=='string'){results.push({ok:false,...target});continue;}
            try {
              if(action==='cancel') results.push({ok:true,...await r.cancelSubagent(target,caller)});
              if(action==='inspect') results.push({ok:true,...r.projectSubagent(target,caller)});
              if(action==='remove') {if(!app.save())throw new Error('Cannot remove worker until its history is archived');results.push(...await r.removeConversations([target],caller));app.cleanupRemoved();}
            } catch(e){results.push({ok:false,subagentId:target,error:String(e)});}
          } break;
        }
        case 'join': {
          const targets=invocation.subagentIds.filter((x:any)=>typeof x==='string');
          if(targets.length!==invocation.subagentIds.length) throw new Error('Invalid or duplicate subagent ID');
          const binding=r.bindSubagentJoin(targets,caller,id);
          let abort:()=>void=()=>{};
          const cancelled=new Promise<never>((_,reject)=>{abort=()=>reject(new Error('Join cancelled'));if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});});
          try {
            app.releaseScreen?.(parent?.conversationId??'main');
          const wait=()=>Promise.race([binding.completion,cancelled]);
            await (parent?r.scheduler.suspendConversationSlotDuring(parent,wait):wait());
            results=binding.finalizeCollection('model').map((x:any)=>({ok:true,...x}));
          } catch(e){binding.interrupt?.(String(e));throw e;}
          finally {binding.release();signal?.removeEventListener('abort',abort);}
          break;
        }
      }
      app.save();app.publish();
      return toolResult({action,results});
    },
  };
}
