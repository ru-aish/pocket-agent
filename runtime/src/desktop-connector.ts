import {createHash,randomUUID} from 'node:crypto';
import {validateToolArguments} from '@earendil-works/pi-ai';

export interface DesktopConnector {
  open(owner:string,names:readonly string[],signal:AbortSignal):{sessionId:string;close():void};
}
type Binding={owner:string;names:Set<string>;signal:AbortSignal;calls:number;principal?:string};
type Receipt={fingerprint:string;state:'started'|'finished';result?:any};

/** Native ChatGPT owns the MCP protocol; PocketAgent owns execution and worker grants. */
export class DesktopToolRouter implements DesktopConnector {
  private readonly bindings=new Map<string,Binding>();
  private readonly pending=new Map<string,Promise<any>>();
  private receipts:Record<string,Receipt>={};
  constructor(private readonly host:{tools(owner:string):any[];persist():boolean;record(owner:string,name:string,callId:string,args:any,result?:any):void}){}
  restore(receipts:Record<string,Receipt>|undefined):void {this.receipts=receipts??{};}
  snapshot():Record<string,Receipt>{return this.receipts;}
  get active():boolean{return this.pending.size>0;}
  open(owner:string,names:readonly string[],signal:AbortSignal) {
    signal.throwIfAborted();
    if(this.bindings.size>=16)throw Error('Too many active ChatGPT MCP sessions');
    if(!this.bindings.size&&!this.pending.size)this.receipts={};
    const local=new AbortController();
    const sessionId=randomUUID(),binding:Binding={owner,names:new Set(names),signal:AbortSignal.any([signal,local.signal]),calls:0};
    this.bindings.set(sessionId,binding);
    const close=()=>{this.bindings.delete(sessionId);signal.removeEventListener('abort',close);local.abort();};
    signal.addEventListener('abort',close,{once:true});return {sessionId,close};
  }
  list():any[] {
    return this.host.tools('*').map(tool=>({name:tool.name,description:tool.description,
      inputSchema:{...tool.parameters,properties:{...tool.parameters.properties,
        pocket_session:{type:'string',description:'Use the exact PocketAgent MCP session ID supplied in this turn.'},
        pocket_request:{type:'string',minLength:1,maxLength:128,description:'Unique ID for this logical tool call. Reuse it with identical arguments when checking an uncertain result; never repeat a mutation with a new ID.'}},
        required:[...(tool.parameters.required??[]),'pocket_session','pocket_request']},
      securitySchemes:[{type:'oauth2',scopes:['pocket.tools']}]}));
  }
  async call(principal:string,name:string,input:any):Promise<any> {
    const {pocket_session:sessionId,pocket_request:requestId,...args}=input??{};
    if(typeof sessionId!=='string'||typeof requestId!=='string'||!/^[a-zA-Z0-9._:-]{1,128}$/.test(requestId))throw Error('PocketAgent session and request IDs are required');
    const binding=this.bindings.get(sessionId);
    if(!binding||binding.signal.aborted)throw Error('PocketAgent session is inactive. Start or resume the task in PocketAgent.');
    if(binding.principal&&binding.principal!==principal)throw Error('This session belongs to another authorized connection');
    binding.principal=principal;
    const tool=binding.names.has(name)?this.host.tools(binding.owner).find(tool=>tool.name===name):undefined;
    if(!tool)throw Error('Tool is not granted to this PocketAgent worker');
    const call={type:'toolCall' as const,id:requestId,name,arguments:args};
    const validated=validateToolArguments(tool,call);
    const key=createHash('sha256').update(sessionId+'\0'+requestId).digest('hex');
    const fingerprint=createHash('sha256').update(JSON.stringify({name,args:validated})).digest('hex');
    const receipt=this.receipts[key];
    if(receipt){
      if(receipt.fingerprint!==fingerprint)throw Error('Request ID was already used with different arguments');
      if(this.pending.has(key))return this.pending.get(key);
      if(receipt.state==='finished')return receipt.result;
      throw Error('Previous execution outcome is unknown. Inspect phone state; this action was not repeated.');
    }
    if(++binding.calls>800)throw Error('ChatGPT MCP tool budget reached');
    if(Object.keys(this.receipts).length>=800||JSON.stringify(this.receipts).length>4_000_000)throw Error('ChatGPT MCP receipt budget reached; finish the current sessions before starting more work');
    this.receipts[key]={fingerprint,state:'started'};
    if(!this.host.persist()){delete this.receipts[key];throw Error('Cannot persist tool reservation. Nothing was executed.');}
    const callId=sessionId+':'+requestId;
    this.host.record(binding.owner,name,callId,validated);
    const pending=Promise.resolve().then(async()=>{
      binding.signal.throwIfAborted();
      let result:any;
      try {result=await tool.execute(callId,validated,binding.signal);}
      catch(error){result={isError:true,content:[{type:'text',text:String((error as Error)?.message??'Tool failed')} ]};}
      // Large screenshots stay in the live response, never in the durable duplicate receipt.
      const retained={...result,content:result.content?.filter((block:any)=>block.type!=='image')};
      if(JSON.stringify(retained).length>64000)throw Error('Tool result is too large to retain; inspect device state before retrying');
      this.host.record(binding.owner,name,callId,validated,result);
      this.receipts[key]={fingerprint,state:'finished',result:retained};
      if(!this.host.persist())throw Error('Tool finished but its receipt could not be saved. Inspect state before retrying.');
      return result;
    }).finally(()=>{this.pending.delete(key);});
    this.pending.set(key,pending);return pending;
  }
}
