import { McpClient, StreamableHttpTransport, toLlmContent, type ListToolsResult, type Tool } from '@earendil-works/pi-mcp';
import { Type } from 'typebox';

const MAX_SERVERS=10, MAX_TOOLS=100, MAX_TEXT=32_000, MAX_IMAGE=2_000_000, MAX_JSON_RESPONSE=4*1024*1024;
type McpHeader={name:string,value:string};
type McpServer={id:string,name:string,url:string,enabled?:boolean,authMode?:'none'|'bearer'|'headers',bearerToken?:string,headers?:McpHeader[],headerName?:string,headerValue?:string};
type RemoteClient=Pick<McpClient,'close'|'callTool'>;
type Entry={server:McpServer,client:RemoteClient,tools:Tool[]};
export type McpConnector=(server:McpServer)=>Promise<{client:RemoteClient,tools:Tool[]}>;
export function isHindsightBank(server:McpServer):boolean {
  try {
    return (server.id==='hindsight'||server.name.trim().toLowerCase()==='hindsight')
      && /\/mcp\/[^/]+\/?$/.test(new URL(server.url).pathname);
  } catch {return false;}
}
const safeIdentifier=(value:string)=>value.replace(/[^A-Za-z0-9_-]/g,'_').slice(0,40)||'tool';
const delay=(ms:number)=>new Promise<void>(resolve=>setTimeout(resolve,ms));
export async function boundMcpJsonResponse(response:Response):Promise<Response> {
  const contentType=(response.headers.get('content-type')??'').toLowerCase();
  if(!response.body||(!contentType.includes('application/json')&&!contentType.includes('+json')))return response;
  const declared=Number(response.headers.get('content-length'));
  if(Number.isFinite(declared)&&declared>MAX_JSON_RESPONSE){await response.body.cancel().catch(()=>{});throw new Error('MCP JSON response exceeds 4 MB');}
  const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
  try {
    while(true){const next=await reader.read();if(next.done)break;size+=next.value.byteLength;if(size>MAX_JSON_RESPONSE){await reader.cancel().catch(()=>{});throw new Error('MCP JSON response exceeds 4 MB');}chunks.push(next.value);}
  } catch(error) {try{await reader.cancel();}catch{}throw error;}
  const body=new Uint8Array(size);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.byteLength;}
  return new Response(body,{status:response.status,statusText:response.statusText,headers:response.headers});
}
async function remoteFetch(input:RequestInfo|URL,init?:RequestInit):Promise<Response>{return boundMcpJsonResponse(await fetch(input,{...init,redirect:'error'}));}
async function listBoundedTools(client:McpClient):Promise<Tool[]> {
  const tools:Tool[]=[],cursors=new Set<string>();let cursor:string|undefined,pages=0;
  do {
    if(++pages>10)throw new Error('MCP tool listing exceeded 10 pages');
    const page=await client.request<ListToolsResult>('tools/list',cursor?{cursor}:{},{timeoutMs:15000});
    if(!Array.isArray(page.tools))throw new Error('MCP server returned an invalid tool list');
    if(tools.length+page.tools.length>MAX_TOOLS)throw new Error(`MCP server advertises more than ${MAX_TOOLS} tools`);
    tools.push(...page.tools);
    cursor=page.nextCursor;
    if(cursor){if(cursor.length>2048||cursors.has(cursor))throw new Error('MCP server returned an invalid pagination cursor');cursors.add(cursor);}
  } while(cursor);
  return tools;
}

export function normalizeMcpSchema(value:any):any {
  const text=JSON.stringify(value??{});
  if(text.length>16_000)throw new Error('MCP tool schema exceeds 16 KB');
  const parsed=JSON.parse(text);
  if(!parsed||typeof parsed!=='object'||Array.isArray(parsed)||parsed.type&&parsed.type!=='object')
    throw new Error('MCP tool schema must be a JSON object schema');
  return {...parsed,type:'object',properties:parsed.properties&&typeof parsed.properties==='object'&&!Array.isArray(parsed.properties)?parsed.properties:{}};
}
export function validateMcpServer(server:McpServer):void {
  const url=new URL(server.url);
  if(url.protocol!=='https:'||url.username||url.password||url.hash||url.search||url.href.length>2048)throw new Error('MCP server must use an HTTPS URL without embedded credentials, query or fragment');
  if(!server.id||server.id.length>64||!server.name||server.name.length>60)throw new Error('MCP server configuration is invalid');
  const mode=server.authMode??(server.headerName?'headers':'none');
  if(!['none','bearer','headers'].includes(mode))throw new Error('MCP authentication mode is invalid');
  if((server.bearerToken??'').length>2000||/[\r\n\0]/.test(server.bearerToken??''))throw new Error('MCP authentication value is invalid');
  const headers=server.headers??(server.headerName?[{name:server.headerName,value:server.headerValue??''}]:[]);
  if(headers.length>8)throw new Error('MCP supports up to 8 custom headers');
  const headerNames=new Set<string>();
  for(const header of headers){
    if(!header||typeof header.name!=='string'||typeof header.value!=='string'||header.name.length>100||header.value.length>2000||/[\r\n\0]/.test(header.value)||!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$/.test(header.name))throw new Error('MCP authentication header is invalid');
    const key=header.name.toLowerCase();if(headerNames.has(key))throw new Error('MCP custom header names must be unique');headerNames.add(key);
  }
  if(mode==='none'&&(server.bearerToken||headers.length>0||server.headerValue)||mode==='bearer'&&(headers.length>0||server.headerValue)||mode==='headers'&&server.bearerToken)
    throw new Error('MCP authentication header fields do not match the selected mode');
}
export function mcpAuthHeaders(server:McpServer):Record<string,string>{
  const mode=server.authMode??(server.headerName?'headers':'none');
  if(mode==='bearer')return server.bearerToken?{Authorization:`Bearer ${server.bearerToken.replace(/^Bearer\s+/i,'')}`} : {};
  if(mode==='headers')return Object.fromEntries((server.headers??(server.headerName?[{name:server.headerName,value:server.headerValue??''}]:[])).map(header=>[header.name,header.value]));
  return {};
}
function sanitizeJson(value:any,sanitize:(value:string)=>string,depth=0):any {
  if(typeof value==='string')return sanitize(value).slice(0,MAX_TEXT);
  if(depth>=8)return '[truncated]';
  if(Array.isArray(value))return value.slice(0,100).map(item=>sanitizeJson(item,sanitize,depth+1));
  if(value&&typeof value==='object') {
    const result:any={};
    for(const [key,child] of Object.entries(value).slice(0,100))result[key.slice(0,120)]=sanitizeJson(child,sanitize,depth+1);
    return result;
  }
  return value;
}

/** Owns remote clients and turns their advertised tools into bounded Pi tools. */
export class McpToolRegistry {
  private entries:Entry[]=[];
  private dynamic:any[]=[];
  private errors=new Map<string,string>();
  private refreshQueue:Promise<void>=Promise.resolve();
  private connecting=new Set<RemoteClient>();
  private closed=false;
  constructor(private readonly sanitize:(value:string)=>string=(value)=>value,
      private readonly connector?: McpConnector){}
  get tools():any[]{return this.dynamic;}
  get toolNames():string[]{return this.dynamic.map(tool=>tool.name);}
  status():any[]{return this.entries.map(entry=>({id:entry.server.id,name:entry.server.name,tools:entry.tools.map(tool=>tool.name)}));}
  errorsForSettings():any[]{return [...this.errors].map(([id,error])=>({id,error}));}
  /** A bank-scoped Hindsight MCP is opt-in via the saved server name "Hindsight". */
  async hindsightCall(name:'retain'|'recall',target:string,args:Record<string,unknown>):Promise<string|null>{
    const entry=this.entries.find(item=>isHindsightBank(item.server)&&item.server.url===target
      &&item.tools.some(tool=>tool.name===name));
    if(!entry)return null;
    const result=await entry.client.callTool(name,args,{timeoutMs:name==='recall'?5_000:10_000});
    if(result.isError)throw new Error(`Hindsight ${name} failed`);
    if(name==='retain')return 'accepted';
    return toLlmContent(result).filter((block:any)=>block.type==='text')
      .map((block:any)=>this.sanitize(String(block.text))).join('\n').slice(0,10_000);
  }
  async close():Promise<void>{
    this.closed=true;
    const current=[...this.connecting,...this.entries.map(entry=>entry.client)];
    this.entries=[];this.dynamic=[];this.errors.clear();
    await Promise.race([Promise.allSettled(current.map(client=>client.close())),delay(250)]);
    await Promise.race([this.refreshQueue,delay(250)]);
    const late=[...this.connecting,...this.entries.map(entry=>entry.client)];
    this.entries=[];this.dynamic=[];this.errors.clear();
    await Promise.race([Promise.allSettled(late.map(client=>client.close())),delay(250)]);
  }
  private async closeEntries():Promise<void>{const entries=this.entries;this.entries=[];this.dynamic=[];this.errors.clear();await Promise.race([Promise.allSettled(entries.map(entry=>entry.client.close())),delay(500)]);}
  refresh(configs:McpServer[]):Promise<void>{
    if(this.closed)return Promise.resolve();
    const snapshot=Array.isArray(configs)?configs.map(server=>({...server})):configs;
    const pending=this.refreshQueue.then(()=>this.refreshNow(snapshot));
    this.refreshQueue=pending.catch(()=>{});
    return pending;
  }
  private async refreshNow(configs:McpServer[]):Promise<void>{
    await this.closeEntries();
    if(this.closed)return;
    if(!Array.isArray(configs)||configs.length>MAX_SERVERS)throw new Error('MCP server limit exceeded');
    const names=new Set<string>();
    for(const raw of configs) {
      const server={...raw};
      try {
        validateMcpServer(server);
        if(names.has(server.id))throw new Error('Duplicate MCP server identifier'); names.add(server.id);
        let client:RemoteClient|undefined,tools:Tool[];
        try {
          if(server.enabled===false)continue;
          if(this.connector){const connected=await this.connector(server);client=connected.client;tools=connected.tools;if(this.closed){await client.close().catch(()=>{});return;}}
          else {
            const headers=mcpAuthHeaders(server);
            const liveClient=new McpClient({name:'Pocket Agent',version:'0.1.0',requestTimeoutMs:15000});
            client=liveClient;this.connecting.add(liveClient);
            await liveClient.connect(new StreamableHttpTransport({url:server.url,headers,openGetStream:false,maxMessageBytes:1_000_000,fetch:remoteFetch}));
            if(this.closed){this.connecting.delete(liveClient);await liveClient.close().catch(()=>{});return;}
            tools=await listBoundedTools(liveClient);
            if(this.closed){this.connecting.delete(liveClient);await liveClient.close().catch(()=>{});return;}
          }
          if(tools.length>MAX_TOOLS)throw new Error(`MCP server advertises more than ${MAX_TOOLS} tools`);
          for(const tool of tools)normalizeMcpSchema(tool.inputSchema);
          if(this.closed){if(client)await client.close().catch(()=>{});return;}
          if(client)this.connecting.delete(client);
          this.entries.push({server,client:client!,tools});
        } catch(error) { if(client){this.connecting.delete(client);await client.close().catch(()=>{});} if(this.closed)return; throw error; }
      } catch(error) {
        const message=this.sanitize(String((error as any)?.message??'MCP connection failed')).slice(0,300);
        this.errors.set(server.id,message||'MCP connection failed');
      }
    }
    if(!this.closed)this.dynamic=this.createTools();
  }
  private createTools():any[]{
    const used=new Set<string>();
    const advertised=this.entries.flatMap(entry=>entry.tools.map(tool=>({entry,tool})))
      .sort((a,b)=>a.entry.server.id.localeCompare(b.entry.server.id)||a.tool.name.localeCompare(b.tool.name));
    for(const {entry,tool} of advertised) {
      const base=`mcp_${safeIdentifier(entry.server.id.slice(0,12))}_${safeIdentifier(tool.name)}`.slice(0,64);
      let name=base,index=2;while(used.has(name)||name.length>64)name=`${base.slice(0,Math.max(1,63-String(index).length))}_${index++}`;
      used.add(name);
      const schema=normalizeMcpSchema(tool.inputSchema);
      this.dynamic.push({
        name,label:(tool.title||tool.name).slice(0,80),
        description:this.sanitize(`${entry.server.name}: ${tool.description||tool.title||tool.name}`.slice(0,1000)),
        parameters:Type.Unsafe(schema),
        execute:async(_id:string,args:any,signal?:AbortSignal)=>{
          try {
            const result=await entry.client.callTool(tool.name,args,{signal,timeoutMs:30000});
            let textBudget=MAX_TEXT,imageBudget=MAX_IMAGE;
            const blocks=toLlmContent(result).slice(0,40).flatMap((block:any)=>{
              if(block.type==='text'&&textBudget>0){const text=this.sanitize(String(block.text)).slice(0,textBudget);textBudget-=text.length;return text?[{type:'text',text}]:[];}
              if(block.type==='image'&&typeof block.data==='string'&&block.data.length<=imageBudget&&typeof block.mimeType==='string') {imageBudget-=block.data.length;return [block];}
              return [];
            });
            if(!blocks.length)blocks.push({type:'text',text:'MCP tool returned no readable content.'});
            const details=sanitizeJson({server:entry.server.name,tool:tool.name,content:blocks.filter(x=>x.type==='text')},this.sanitize);
            return {content:blocks,details,isError:result.isError===true};
          } catch(error) {
            const message=this.sanitize(String((error as any)?.message??'MCP tool call failed')).slice(0,500);
            return {content:[{type:'text',text:`MCP tool call failed: ${message}`}],details:{isError:true,message},isError:true};
          }
        },
      });
    }
    return this.dynamic;
  }
  async discover(configs:McpServer[],id:string):Promise<any>{
    const candidate=configs.find(server=>server.id===id);
    if(!candidate)throw new Error('MCP server was not found');
    if(candidate.enabled===false)return {id:candidate.id,name:candidate.name,tools:[]};
    const temp=new McpToolRegistry(this.sanitize,this.connector);
    try {
      await temp.refresh([candidate]);
      const error=temp.errorsForSettings()[0]?.error;
      if(error)throw new Error(error);
      return temp.status()[0]??{id,name:candidate.name,tools:[]};
    } finally {await temp.close();}
  }
}
