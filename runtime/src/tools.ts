import { Type } from 'typebox';
import { randomUUID } from 'node:crypto';
import schemas from '../../app/src/main/assets/tools.json';
import { SubagentParams, parseSubagentInvocation } from '../vendor/pi9/schema.js';

// Keep upstream parsing/contracts intact; advertise only mobile-supported spawn
// inputs. Agent selection still determines the recursively inherited tool grant.
const mobileSpawn:any={...SubagentParams.properties.spawns.items,properties:{...SubagentParams.properties.spawns.items.properties}};
const unsupportedSpawnOverrides=['cwd','skills','thinking'];
for(const field of unsupportedSpawnOverrides)delete mobileSpawn.properties[field];
if(mobileSpawn.required)mobileSpawn.required=mobileSpawn.required.filter((field:string)=>!unsupportedSpawnOverrides.includes(field));
export const MobileSubagentParams=Type.Unsafe({...SubagentParams,properties:{...SubagentParams.properties,spawns:{...SubagentParams.properties.spawns,items:mobileSpawn}}});

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
  // Bound busy accessibility trees without discarding screenshots or receipts.
  const observations:any[]=[];
  const collect=(value:any):void=>{
    if(!value||typeof value!=='object')return;
    if(Array.isArray(value)){for(const entry of value)collect(entry);return;}
    if(Array.isArray(value.nodes))observations.push(value);
    for(const [key,child] of Object.entries(value))if(key!=='nodes')collect(child);
  };
  collect(text);
  let json=JSON.stringify(text??null);
  while(json.length>32000){
    const target=observations.filter(x=>x.nodes.length).sort((a,b)=>b.nodes.length-a.nodes.length)[0];
    if(!target)break;
    const before=target.nodes.length;
    const retained=Math.floor(before/2);
    target.nodes=target.nodes.slice(0,retained);
    target.nodes_omitted=(target.nodes_omitted??0)+before-retained;
    target.nodes_truncated=true;
    json=JSON.stringify(text??null);
  }
  const overLimit=json.length>32000;
  const content:any[]=[{type:'text',text:overLimit?JSON.stringify({isError:true,code:'OUTPUT_LIMIT',message:'Native output exceeded 32 KB; narrow the query.'}):json}];
  if(latestImage)content.push(latestImage);
  return {content,details:overLimit?{isError:true,code:'OUTPUT_LIMIT'}:text,isError:Boolean(data?.isError)||overLimit};
}
export function nativeTools(host:any,owner:string,grant?:readonly string[]):any[] {
  return schemas.filter(s=>!grant || grant.includes(s.name)).map(s=>({
    name:s.name,label:s.name,description:s.description,
    parameters:Type.Unsafe(s.inputSchema),
    execute:async(_id:string,args:any,signal?:AbortSignal)=>{
      if(signal?.aborted) throw new Error('Cancelled before native dispatch');
      // Batch entries must also stay inside this conversation's grant.
      if(s.name==='phone_batch' && args.calls?.some((x:any)=>!schemas.some(t=>t.name===x.name) || (grant && !grant.includes(x.name)))) throw new Error('Batch tool exceeds tool grant');
      if(s.name==='phone_screen'){
        if(args.action==='release'){
          host.releaseScreen?.(owner);
          return toolResult({status:'released'});
        }
        if(args.action==='acquire'&&host.ensureDisplay)return toolResult(await host.ensureDisplay(owner,signal));
      }
      const needsUi=s.name==='phone_observe'||s.name==='phone_act'||s.name==='phone_open_app'||s.name==='phone_open_url'||s.name==='phone_clock_screen'||
        (s.name==='phone_batch'&&args.calls?.some((c:any)=>['phone_observe','phone_act','phone_open_app','phone_open_url','phone_clock_screen'].includes(c.name)));
      if(needsUi&&host.ensureDisplay){
        const result=await host.ensureDisplay(owner,signal);
        if(result?.isError)return toolResult(result);
      }
      if(signal?.aborted)throw new Error('Cancelled before native dispatch');
      if(s.name==='phone_observe'||(s.name==='phone_batch'&&args.calls?.some((x:any)=>x.name==='phone_observe')))host.dropPriorImages(owner);
      const result=host.call('tool',{name:s.name,args,owner});
      if(needsUi&&result?.isError&&result.code==='NOT_READY'&&
        /virtual display|no virtual display|shizuku backend|display service/i.test(String(result.message??'')))
        host.releaseScreen?.(owner);
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
  return {name:'subagent',label:'Subagent',description:'Use action=models to discover provider models, then specify a listed model in spawns[].model for an independent worker. Omitting model inherits the main model. Delegate asynchronously. spawn returns immediately. End your turn and remain available; host delivers completion. inspect does not collect. join collects; use only for explicit dependencies. resume requires the initiator collection receipt. Tools are inherited by children.',parameters:MobileSubagentParams,
    execute:async(id:string,params:any,signal?:AbortSignal)=>{
      const invocation=parseSubagentInvocation(params,{maxTasks:4});
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
            const h=app.startTasks([task],{caller,initiatedBy:'model'});
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
              if(action==='remove') {results.push(...await r.removeConversations([target],caller));app.cleanupRemoved();}
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
