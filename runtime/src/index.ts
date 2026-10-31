import { PocketRuntime } from './runtime.js';
declare const PocketNative:any;
try {
 const runtime=new PocketRuntime(PocketNative);
 (globalThis as any).PocketAgent={dispatch:(json:string)=>runtime.dispatch(json),connectorList:()=>runtime.connectorToolList(),connectorCall:(requestId:string,principal:string,name:string,args:any)=>runtime.connectorToolCall(requestId,principal,name,args),debugList:()=>runtime.debugToolList(),debugCall:(requestId:string,name:string,args:any)=>runtime.debugToolCall(requestId,name,args)};
} catch(error) {
 PocketNative.emit(JSON.stringify({type:'error',error:String(error)}));
 throw error;
}
