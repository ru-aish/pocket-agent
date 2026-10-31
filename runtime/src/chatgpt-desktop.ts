import {createHash, randomUUID} from 'node:crypto';
import {pocketApproval} from './desktop-approval.js';
import type {DesktopConnector} from './desktop-connector.js';
import {normalizeContext,getCurrentTools,getCurrentSystemPrompt} from '@earendil-works/pi-ai/utils/transcript';
import {AssistantMessageEventStream} from '@earendil-works/pi-ai/utils/event-stream';
import type {AssistantMessage, Context, Model} from '@earendil-works/pi-ai';
import {rendererDiscovery} from '../vendor/t3-chatgpt/renderer-discovery.js';
import {normalizeStreamMessage, mergeFinalAnswer, imageUploadDestination} from '../vendor/t3-chatgpt/protocol.js';

export interface NativeUpdate {
  kind: 'update'|'complete'|'error'|'disconnected';
  conversationId: string|null;
  requestId?: string;
  messageId?: string;
  role?: string;
  channel?: string;
  recipient?: string;
  text?: string;
  status?: string;
  endTurn?: boolean;
  hidden?: boolean;
  createdAt?: number;
  fedoraApproval?: {messageId:string;connectorId:string;linkId:string};
}

/** Direct CDP grants account control. Accept private tailnet and local addresses only. */
export function desktopEndpoint(value: string|undefined): string {
  if (!value) throw new Error('Enter the desktop’s Tailscale endpoint.');
  const url=new URL(value);
  const parts=url.hostname.split('.').map(Number);
  const tailnet=parts.length===4 && parts.every(n=>Number.isInteger(n)&&n>=0&&n<=255)
    && parts[0]===100 && parts[1]>=64 && parts[1]<=127;
  const local=['127.0.0.1','localhost','[::1]'].includes(url.hostname);
  if (!['http:','https:'].includes(url.protocol)||(!tailnet&&!local)
    ||url.username||url.password||url.search||url.hash||!['','/'].includes(url.pathname))
    throw new Error('Use an HTTP(S) Tailscale IPv4 or loopback desktop endpoint without credentials or a path.');
  return url.origin;
}

export const DESKTOP_THINKING_EFFORTS=['minimal','low','medium','high','xhigh','max','standard','extended'] as const;
const thinkingEfforts=new Set<string>(DESKTOP_THINKING_EFFORTS);

class DesktopInferenceError extends Error {}

type Pending={resolve:(value:any)=>void;reject:(error:Error)=>void;timer:ReturnType<typeof setTimeout>};
export class DesktopCdp {
  private next=0;
  private pending=new Map<number,Pending>();
  private constructor(private socket:WebSocket, readonly onEvent:(method:string,params:any)=>void) {
    socket.addEventListener('message',event=>{
      if (String(event.data).length>32*1024*1024) {this.close();return;}
      let message:any;
      try {message=JSON.parse(String(event.data));} catch {this.close();return;}
      if (typeof message.id==='number') {
        const waiter=this.pending.get(message.id);
        if (!waiter) return;
        clearTimeout(waiter.timer);this.pending.delete(message.id);
        message.error?waiter.reject(new Error('Desktop command failed.')):waiter.resolve(message.result);
      } else onEvent(message.method,message.params);
    });
    socket.addEventListener('close',()=>{
      for(const waiter of this.pending.values()){clearTimeout(waiter.timer);waiter.reject(new Error('Desktop disconnected.'));}
      this.pending.clear();onEvent('disconnected',{});
    });
  }
  static async connect(endpoint:string,onEvent:(method:string,params:any)=>void=()=>{},signal?:AbortSignal,fetcher:typeof fetch=fetch) {
    const origin=desktopEndpoint(endpoint);
    const response=await fetcher(origin+'/json/list',{redirect:'error',signal:AbortSignal.any([AbortSignal.timeout(15000),...(signal?[signal]:[])])});
    if (!response.ok) throw new Error('Desktop discovery failed. Check Tailscale and the desktop debug port.');
    const body=await response.text();
    if(body.length>1024*1024)throw new Error('Desktop discovery exceeded its size limit.');
    const targets=JSON.parse(body);
    const target=Array.isArray(targets)&&targets.find(t=>t.type==='page'&&t.url==='app://-/index.html');
    if (!target?.webSocketDebuggerUrl) throw new Error('The native ChatGPT desktop renderer is unavailable.');
    const advertised=new URL(target.webSocketDebuggerUrl);
    if(!/^\/devtools\/page\/[A-Za-z0-9-]+$/.test(advertised.pathname)||advertised.search||advertised.hash)
      throw new Error('Invalid desktop renderer identity.');
    // Discovery can advertise loopback behind a proxy. Never follow an arbitrary advertised host.
    const url=new URL(advertised.pathname,origin);url.protocol=url.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(url);
    await new Promise<void>((resolve,reject)=>{
      const cleanup=()=>{clearTimeout(timer);socket.removeEventListener('open',open);socket.removeEventListener('error',error);socket.removeEventListener('close',error);signal?.removeEventListener('abort',abort);};
      const fail=(message:string)=>{cleanup();socket.close();reject(new Error(message));};
      const open=()=>{cleanup();resolve();};
      const error=()=>fail('Could not connect to the desktop renderer.');
      const abort=()=>fail('Request cancelled.');
      const timer=setTimeout(()=>fail('Desktop handshake timed out.'),15000);
      socket.addEventListener('open',open,{once:true});socket.addEventListener('error',error,{once:true});socket.addEventListener('close',error,{once:true});
      signal?.addEventListener('abort',abort,{once:true});if(signal?.aborted)abort();
    });
    const cdp=new DesktopCdp(socket,onEvent);
    if(signal){
      const abort=()=>{cdp.interruptPending();cdp.close();};
      signal.addEventListener('abort',abort,{once:true});
      socket.addEventListener('close',()=>signal.removeEventListener('abort',abort),{once:true});
      if(signal.aborted)abort();
    }
    return cdp;
  }
  call(method:string,params:Record<string,unknown>={},timeout=90000):Promise<any> {
    if(this.socket.readyState!==WebSocket.OPEN)return Promise.reject(new Error('Desktop disconnected.'));
    if(this.pending.size>=16)return Promise.reject(new Error('Too many pending desktop commands.'));
    const id=++this.next;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);this.onEvent('timeout',{method});reject(new Error('Desktop command timed out.'));},timeout);
      this.pending.set(id,{resolve,reject,timer});
      try{this.socket.send(JSON.stringify({id,method,params}));}catch{clearTimeout(timer);this.pending.delete(id);reject(new Error('Desktop disconnected.'));}
    });
  }
  async evaluate<T=any>(expression:string,timeout=90000):Promise<T> {
    const result=await this.call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true},timeout);
    if(result.exceptionDetails)throw new Error('The desktop operation failed. Check its sign-in and availability.');
    return result.result?.value;
  }
  interruptPending(){for(const waiter of this.pending.values()){clearTimeout(waiter.timer);waiter.reject(new Error('Request cancelled.'));}this.pending.clear();}
  close(){this.socket.close();}
}

export async function desktopModels(endpoint:string,fetcher:typeof fetch=fetch,signal:AbortSignal=AbortSignal.timeout(30000)) {
  const cdp=await DesktopCdp.connect(endpoint,undefined,signal,fetcher);
  try {
    const raw=await cdp.evaluate(`(async()=>{${rendererDiscovery}\nconst r=await resolveClient();return collectModels(await r.client.models()).models;})()`);
    const entries=Array.isArray(raw)?raw:raw.models;
    if(!Array.isArray(entries))throw new Error('The desktop did not return a model catalog.');
    const seen=new Set<string>();
    const models=entries.flatMap((entry:any)=>{
      const id=entry.slug??entry.id;
      if(entry.is_available===false||entry.visibility==='hide'||typeof id!=='string'||!/^[A-Za-z0-9._:/+@-]{1,160}$/.test(id)||seen.has(id))return [];
      seen.add(id);
      const levels=Array.isArray(entry.thinking_efforts)?entry.thinking_efforts.filter((v:unknown)=>typeof v==='string'&&thinkingEfforts.has(v)):[];
      return [{id,name:typeof entry.name==='string'?entry.name:typeof entry.title==='string'?entry.title:id,
        ...(levels.length?{supportedReasoningLevels:levels,defaultReasoningLevel:levels[0]}:{})}];
    }).slice(0,1024);
    if(!models.length)throw new Error('The desktop returned no available models.');
    return models;
  } finally{cdp.close();}
}

export function desktopPrompt(context:Context,sessionId?:string) {
  const images:Array<{data:string;mimeType:string}>=[];
  const transcript=normalizeContext(context);
  const tools=getCurrentTools(transcript.messages);
  if(tools.length&&!sessionId)throw Error('Enable ChatGPT MCP in PocketAgent Settings and connect it to this ChatGPT account before using tools.');
  const systemPrompt=getCurrentSystemPrompt(transcript.messages);
  const messages=transcript.messages.filter(message=>message.role!=='system').map((message:any)=>({role:message.role,
    ...(message.toolName?{toolName:message.toolName,toolCallId:message.toolCallId,isError:message.isError}:{}),
    content:typeof message.content==='string'?message.content:message.content?.map((part:any)=>{
      if(part.type!=='image')return part;
      const index=images.push({data:part.data,mimeType:part.mimeType});return {type:'text',text:`[Attached image ${index}]`};
    })}));
  const payload=JSON.stringify({systemPrompt,messages});
  if(payload.length>2_000_000)throw new Error('Desktop context limit reached. Saved conversation was retained; start a new chat.');
  if(images.length>8)throw new Error('Desktop accepts at most eight images per inference request.');
  const instruction=tools.length
    ?`You are the PocketAgent assistant. Follow the supplied system prompt and conversation. Use the connected PocketAgent MCP app for tools. Your exact pocket_session is ${sessionId}. Include it on every PocketAgent tool call, together with a unique pocket_request for each logical operation. Reuse the same request ID and identical arguments when checking an uncertain call; never repeat a mutation with a new ID. Only these tools are granted to this session: ${tools.map(tool=>tool.name).join(', ')}. Tool definitions and execution come from the MCP connector, not from text you output. Use real MCP tool calls; never output JSON pretending to call a tool and never invent results. If the PocketAgent connector is missing or asks for authorization, tell the user to connect/approve PocketAgent in ChatGPT. Do not use Terminal MCP or another connector for these phone actions.`
    :'You are the inference engine for PocketAgent. Follow the supplied system prompt and conversation. No tools are available: do not use ChatGPT connectors or native tools. Return your answer directly as text.';
  return {text:instruction+'\n\nPocketAgent context:\n'+payload+'\n\nContinue the active conversation now. Respond normally to the user after completing any necessary real MCP calls.',images,tools};
}

async function uploadImage(cdp:DesktopCdp,image:{data:string;mimeType:string},signal:AbortSignal,fetcher:typeof fetch) {
  const bytes=Buffer.from(image.data,'base64');
  if(!bytes.length||bytes.length>20*1024*1024||!/^image\/(png|jpeg|webp|gif)$/.test(image.mimeType))throw new Error('Unsupported desktop image.');
  const file={name:'pocket-image-'+randomUUID()+'.'+image.mimeType.split('/')[1],size:bytes.length,mimeType:image.mimeType};
  const dimensions=await cdp.evaluate(`new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve({width:image.naturalWidth,height:image.naturalHeight});image.onerror=()=>reject(Error('Invalid image'));image.src=${JSON.stringify('data:'+image.mimeType+';base64,'+image.data)};})`);
  const grant=await cdp.evaluate(`(async()=>{${rendererDiscovery}\nconst r=await resolveClient();return r.client.createUpload(${JSON.stringify(file)});})()`);
  if(typeof grant.file_id!=='string'||typeof grant.upload_url!=='string')throw new Error('Desktop image upload grant is unavailable.');
  const destination=imageUploadDestination(grant.upload_url,image.mimeType);
  const response=await fetcher(destination.url,{method:'PUT',body:bytes,headers:{...destination.headers,...grant.upload_headers},redirect:'error',signal});
  if(!response.ok)throw new Error('Desktop image upload failed.');
  await cdp.evaluate(`(async()=>{${rendererDiscovery}\nconst r=await resolveClient();await r.client.finishUpload(${JSON.stringify({...file,id:grant.file_id})});return true;})()`);
  return {...file,...dimensions,id:grant.file_id};
}

export function desktopStream(model:Model<any>,context:Context,owner:string,effort:string|undefined,options:{signal?:AbortSignal;fetch?:typeof fetch;effortVerified?:boolean;connector?:DesktopConnector}={}) {
  const stream=new AssistantMessageEventStream();
  const output:AssistantMessage={role:'assistant',api:model.api,provider:model.provider,model:model.id,content:[],timestamp:Date.now(),stopReason:'stop',
    usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
  void(async()=>{
    const signal=AbortSignal.any([AbortSignal.timeout(900000),...(options.signal?[options.signal]:[])]);
    const fetcher=options.fetch??fetch;
    const requestId=randomUUID(),binding='__pocket_chatgpt_'+requestId.replaceAll('-','');
    let cdp:DesktopCdp|undefined,conversationId:string|null=null,finished=false,submitted=false;
    let prompt:ReturnType<typeof desktopPrompt>;
    let toolSession:ReturnType<DesktopConnector['open']>|undefined;
    let rawText='',visible='',thinking='',textIndex=-1,thinkingIndex=-1;
    const finalBlocks=new Map<string,string>(),thinkingBlocks=new Map<string,string>();
    const mergeBlock=(blocks:Map<string,string>,event:NativeUpdate)=>{
      const id=event.messageId??'default',previous=blocks.get(id),incoming=event.text??previous??'';
      if(!previous||!previous.startsWith(incoming)||incoming.length>=previous.length)blocks.set(id,incoming);
      if(blocks.size>2048||[...blocks.values()].reduce((bytes,block)=>bytes+block.length,0)>4_000_000)throw new DesktopInferenceError('Desktop retained response exceeded its size limit.');
      const merged=[...blocks.values()].reduce((text,block)=>mergeFinalAnswer(text,block),'');
      if(merged.length>2_000_000)throw new DesktopInferenceError('Desktop response exceeded its size limit.');
      return merged;
    };
    let notify:()=>void=()=>{};
    let disconnected=false,connectionGeneration=0;
    const wait=()=>new Promise<void>(resolve=>{
      const timer=setTimeout(resolve,1000);
      notify=()=>{clearTimeout(timer);resolve();};
    });
    const update=(event:NativeUpdate)=>{
      if(signal.aborted||(finished&&event.kind==='update'))return;
      if(event.conversationId)conversationId=event.conversationId;
      if(event.kind==='error')throw new DesktopInferenceError('Desktop inference failed. Check account limits and model availability.');
      if(event.kind==='complete'){finished=true;return;}
      if(event.role!=='assistant'||event.hidden||(event.recipient&&event.recipient!=='all'))return;
      if(event.channel==='analysis') {
        const next=mergeBlock(thinkingBlocks,event);if(!next.startsWith(thinking))return;
        if(thinkingIndex<0){thinkingIndex=output.content.length;output.content.push({type:'thinking',thinking:''});stream.push({type:'thinking_start',contentIndex:thinkingIndex,partial:output});}
        const delta=next.slice(thinking.length);thinking=next;(output.content[thinkingIndex] as any).thinking=next;
        if(delta)stream.push({type:'thinking_delta',contentIndex:thinkingIndex,delta,partial:output});
        return;
      }
      if(event.channel&&event.channel!=='final')return;
      rawText=mergeBlock(finalBlocks,event);
      const next=rawText;
      if(!next.startsWith(visible))return;
      if(textIndex<0&&next){textIndex=output.content.length;output.content.push({type:'text',text:''});stream.push({type:'text_start',contentIndex:textIndex,partial:output});}
      if(textIndex>=0){const delta=next.slice(visible.length);visible=next;(output.content[textIndex] as any).text=next;if(delta)stream.push({type:'text_delta',contentIndex:textIndex,delta,partial:output});}
    };
    let eventError:Error|undefined;
    const connect=async()=>{
      disconnected=false;const generation=++connectionGeneration;
      cdp=await DesktopCdp.connect(model.baseUrl,(method,params)=>{
        if(generation!==connectionGeneration)return;
        if(method==='disconnected'||method==='timeout'||method==='Runtime.executionContextsCleared'||method==='Inspector.detached'){disconnected=true;notify();return;}
        if(method!=='Runtime.bindingCalled'||params?.name!==binding)return;
        try{update(JSON.parse(params.payload));}catch(error){eventError=error instanceof Error?error:new Error('Desktop stream failed.');}notify();
      },signal,fetcher);
      await cdp.call('Runtime.addBinding',{name:binding});
    };
    const stop=async()=>{
      if(!submitted||!cdp)return;
      const cancel=async(connection:DesktopCdp)=>connection.evaluate(`(async()=>{const cancelKey=Symbol.for('pocket.chatgpt.cancelled.v1');window[cancelKey] ||= new Map();window[cancelKey].set(${JSON.stringify(requestId)},Date.now());while(window[cancelKey].size>256)window[cancelKey].delete(window[cancelKey].keys().next().value);const states=window[Symbol.for('pocket.chatgpt.requests.v1')];const s=states?.get(${JSON.stringify(requestId)});if(!s)return false;s.cancelled=true;s.startup.abort();const handle=await Promise.resolve(s.handle).catch(()=>null);try{if(s.conversationId){const c=window[Symbol.for('terminal-mcp.chat-runtime.client.v5')];await c?.stopCompletion?.({conversation_id:s.conversationId,exclude_async_types:[]});}}finally{const client=window[Symbol.for('terminal-mcp.chat-runtime.client.v5')];if(handle?.streamRequestId)client?.cancelStream?.(handle.streamRequestId);}return true;})()`,5000);
      let replacement:DesktopCdp|undefined;
      try{
        if(disconnected)throw new Error('Desktop disconnected.');
        await cancel(cdp);
      }catch{
        try{replacement=await DesktopCdp.connect(model.baseUrl,undefined,AbortSignal.timeout(5000),fetcher);await cancel(replacement);}catch{}
      }finally{replacement?.close();}
    };
    const abort=()=>{notify();cdp?.interruptPending();};signal.addEventListener('abort',abort,{once:true});
    try{
      signal.throwIfAborted();
      const tools=getCurrentTools(normalizeContext(context).messages);
      if(tools.length)toolSession=options.connector?.open(owner,tools.map(tool=>tool.name),signal);
      prompt=desktopPrompt(context,toolSession?.sessionId);await connect();
      const images=[];
      for(const image of prompt.images){signal.throwIfAborted();images.push(await uploadImage(cdp!,image,signal,fetcher));}
      signal.throwIfAborted();stream.push({type:'start',partial:output});
      const messageId=randomUUID();
      const request={action:'next',model:model.id,...(effort&&thinkingEfforts.has(effort)&&(options.effortVerified||effort==='standard'||effort==='extended')?{thinking_effort:effort}:{}),supported_encodings:['v1'],
        timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,timezone_offset_min:-new Date().getTimezoneOffset(),
        messages:[{id:messageId,author:{role:'user'},content:{content_type:images.length?'multimodal_text':'text',parts:[...images.map(image=>({content_type:'image_asset_pointer',asset_pointer:(image.id.startsWith('file_')?'sediment://':'file-service://')+image.id,width:image.width,height:image.height,size_bytes:image.size})),prompt.text]},
          create_time:Date.now()/1000,end_turn:null,recipient:'all',status:'finished_successfully',weight:1,...(images.length?{metadata:{attachments:images}}:{})}]};
      // Retain native state across a dropped CDP socket. Reconnection rebinds this exact request; never resubmit it.
      submitted=true;
      try { await cdp!.evaluate(`(async()=>{${rendererDiscovery}
        const key=Symbol.for('pocket.chatgpt.requests.v1');window[key] ||= new Map();const states=window[key];
        const cancelKey=Symbol.for('pocket.chatgpt.cancelled.v1');const cancelled=(window[cancelKey] ||= new Map());if(cancelled.has(${JSON.stringify(requestId)}))return false;
        const r=await resolveClient();
        for(const [id,s] of states)if(Date.now()-s.createdAt>900000){s.cancelled=true;s.startup.abort();if(s.conversationId)void r.client.stopCompletion({conversation_id:s.conversationId,exclude_async_types:[]}).catch(()=>{});Promise.resolve(s.handle).then(handle=>{if(handle?.streamRequestId)r.client.cancelStream(handle.streamRequestId);}).catch(()=>{});states.delete(id);}
        if([...states.values()].filter(s=>!s.complete&&!s.cancelled).length>=4||states.size>=64)throw Error('Desktop concurrency limit reached');
        if(cancelled?.has(${JSON.stringify(requestId)}))return false;const s={createdAt:Date.now(),id:${JSON.stringify(requestId)},owner:${JSON.stringify(createHash('sha256').update(owner).digest('hex'))},binding:${JSON.stringify(binding)},conversationId:null,updates:new Map(),complete:false,cancelled:false,startup:new AbortController()};states.set(s.id,s);
        const emit=(kind,value)=>{if(s.cancelled)return;const v=value&&typeof value==='object'?value:{};const candidate=v.message||v.data||v;s.conversationId=v.conversation_id||v.conversationId||candidate.conversation_id||candidate.conversationId||s.conversationId;
          let normalized;try{const __name=value=>value;normalized=(${normalizeStreamMessage.toString()})(value,'pocket-desktop');}catch{normalized={};kind='error';}const event={...normalized,kind,conversationId:s.conversationId};if(kind==='update'&&normalized.role==='assistant'&&(!normalized.channel||normalized.channel==='final')&&normalized.endTurn)s.sawFinal=true;if(normalized.messageId)s.updates.set(normalized.messageId,event);if(s.updates.size>2048||JSON.stringify([...s.updates.values()]).length>4_000_000){kind='error';event.kind='error';}s.last=event;if(kind==='complete'||kind==='error')s.complete=true;if(typeof window[s.binding]==='function')window[s.binding](JSON.stringify(event));};
        const initialRequest=${JSON.stringify(request)},sessionId=${JSON.stringify(toolSession?.sessionId??'')},names=${JSON.stringify(prompt.tools.map(tool=>tool.name))};
        const __name=value=>value;const approval=value=>(${pocketApproval.toString()})(value,sessionId,names);
        s.attempted=new Set();s.segment=0;
        const start=next=>{
          if(s.cancelled)return;s.sawFinal=false;const segment=++s.segment;
          s.handle=r.client.startCompletionStream({request:next,startupSignal:s.startup.signal,
            onUpdate:v=>{if(segment===s.segment)emit('update',v);},
            onError:v=>{if(segment===s.segment)emit('error',v);},
            onComplete:v=>{if(segment!==s.segment||s.cancelled)return;void(async()=>{
              if(sessionId&&s.conversationId&&!s.sawFinal){
                const raw=await r.client.get(s.conversationId);const c=raw.conversation||raw.data||raw;
                const head=c.mapping?.[c.current_node]?.message,pending=approval(head);
                if(pending){
                  const seen=new Set();let id=c.current_node,owns=false;
                  while(id&&!seen.has(id)){seen.add(id);const node=c.mapping?.[id];if(!node)break;if(node.message?.id===${JSON.stringify(messageId)})owns=true;id=node.parent;}
                  if(!owns||s.attempted.has(pending.messageId))throw Error('Unverified or uncertain connector approval');
                  if(s.cancelled)return;s.attempted.add(pending.messageId);
                  const response={...initialRequest,conversation_id:s.conversationId,parent_message_id:pending.messageId,
                    messages:[{id:${JSON.stringify(requestId)}+'-approval-'+s.attempted.size,author:{role:'tool',name:'api_tool.call_tool',metadata:{}},channel:null,content:{content_type:'text',parts:['']},create_time:Date.now()/1000,end_turn:null,
                      metadata:{jit_plugin_data:{from_client:{target_message_id:pending.messageId,type:'allow',remember_answer:false}}},recipient:'all',status:'finished_successfully',weight:1}]};
                  start(response);return;
                }
              }
              emit('complete',v);
            })().catch(()=>emit('error',{}));}
          });Promise.resolve(s.handle).catch(()=>emit('error',{}));
        };start(initialRequest);return true;
      })()`); } catch(error) {if(!disconnected)throw error;}
      let backoff=1000;
      while(!finished){
        signal.throwIfAborted();if(eventError)throw eventError;
        if(disconnected){
          cdp?.close();
          try{
            await connect();
            const state=await cdp!.evaluate(`(()=>{const s=window[Symbol.for('pocket.chatgpt.requests.v1')]?.get(${JSON.stringify(requestId)});if(!s)return null;s.binding=${JSON.stringify(binding)};return {updates:[...s.updates.values()],last:s.last};})()`);
            if(!state){
              if(!conversationId)throw new DesktopInferenceError('Desktop lost this request. It was not resent; check the saved conversation before retrying.');
              const recovered=await cdp!.evaluate(`(async()=>{${rendererDiscovery}const r=await resolveClient();const raw=await r.client.get(${JSON.stringify(conversationId)});const c=raw.conversation||raw.data||raw;const nodes=[];const seen=new Set();let id=c.current_node;while(id){if(seen.has(id)||!c.mapping?.[id])throw Error('Invalid ancestry');seen.add(id);const n=c.mapping[id];if(n.message)nodes.push(n.message);id=n.parent;}const userIndex=nodes.findIndex(n=>n.id===${JSON.stringify(messageId)});if(userIndex<0)return null;const __name=value=>value;const normalize=v=>(${normalizeStreamMessage.toString()})(v,'pocket-desktop');const own=nodes.slice(0,userIndex).reverse().map(normalize);const head=normalize(nodes[0]);return {updates:own,complete:c.async_status===null&&head.role==='assistant'&&head.endTurn===true};})()`);
              if(!recovered?.complete)throw new DesktopInferenceError('Desktop lost this request. It was not resent; check the saved conversation before retrying.');
              for(const event of recovered.updates)update({...event,kind:'update',conversationId});finished=true;continue;
            }
            for(const event of state.updates)update(event);if(state.last)update(state.last);backoff=1000;
          }catch(error){
            if(error instanceof DesktopInferenceError)throw error;
            disconnected=true;signal.throwIfAborted();await new Promise<void>((resolve,reject)=>{
              const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',cancel);resolve();};
              const cancel=()=>{clearTimeout(timer);signal.removeEventListener('abort',cancel);reject(new Error('Request cancelled.'));};
              const timer=setTimeout(done,backoff);signal.addEventListener('abort',cancel,{once:true});if(signal.aborted)cancel();
            });backoff=Math.min(backoff*2,10000);
          }
        }else await wait();
      }
      if(eventError)throw eventError;
      if(!rawText.trim())throw new Error('Desktop returned no final answer. Check whether the PocketAgent connector needs authorization in ChatGPT.');
      if(thinkingIndex>=0)stream.push({type:'thinking_end',contentIndex:thinkingIndex,content:thinking,partial:output});
      if(textIndex<0){textIndex=output.content.length;output.content.push({type:'text',text:rawText});stream.push({type:'text_start',contentIndex:textIndex,partial:output});stream.push({type:'text_delta',contentIndex:textIndex,delta:rawText,partial:output});}
      (output.content[textIndex] as any).text=rawText;stream.push({type:'text_end',contentIndex:textIndex,content:rawText,partial:output});
      (output as any).desktop={conversationId};output.stopReason='stop';stream.push({type:'done',reason:'stop',message:output});stream.end(output);
    }catch(error){
      await stop();const aborted=!!options.signal?.aborted;output.stopReason=aborted?'aborted':'error';
      (output as any).desktop={conversationId};output.content=output.content.filter(part=>part.type!=='toolCall');
      output.errorMessage=aborted?'Request cancelled.':signal.aborted?'Desktop request timed out. No prompt was resent; check Tailscale and desktop sign-in.':error instanceof Error&&/^(Desktop |The desktop |The native |Unsupported desktop |Enable ChatGPT MCP|Start ChatGPT MCP)/.test(error.message)?error.message:'Desktop inference failed. Check the ChatGPT MCP connection and saved tool receipts before retrying; no prompt was resent.';
      stream.push({type:'error',reason:aborted?'aborted':'error',error:output});stream.end(output);
    }finally{
      toolSession?.close();signal.removeEventListener('abort',abort);
      if(cdp&&!disconnected)await cdp.evaluate(`(()=>{const states=window[Symbol.for('pocket.chatgpt.requests.v1')];const s=states?.get(${JSON.stringify(requestId)});if(s&&(s.complete||s.cancelled))states.delete(${JSON.stringify(requestId)});return true;})()`,2000).catch(()=>{});
      cdp?.close();
    }
  })();
  return stream;
}
