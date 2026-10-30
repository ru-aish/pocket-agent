function field(value:unknown,key:string):unknown {return value!==null&&typeof value==='object'?Reflect.get(value,key):undefined;}
function text(value:unknown):string {return typeof value==='string'?value.toLowerCase():'';}
export function codexFailure(status:number|undefined,payload:unknown):Readonly<{message:string;terminal:boolean}> {
 const error=field(payload,'error')??payload,code=text(field(error,'code')),type=text(field(error,'type')),message=text(field(error,'message')??error);
 const matches=(values:readonly string[])=>values.includes(code)||values.includes(type);
 const http=status===undefined?'':` (HTTP ${status})`;
 if(matches(['usage_limit_reached','usage_not_included','insufficient_quota','billing_hard_limit_reached'])||/usage limit|quota.*exceeded/.test(message))return {message:`Codex account usage limit reached${http}. Wait for the limit to reset or use another configured provider. Signing in again does not reset usage.`,terminal:true};
 if(status===401||matches(['invalid_api_key','invalid_token','token_expired','authentication_error']))return {message:`Codex rejected the saved login${http}. Sign in again to renew access.`,terminal:true};
 if(matches(['context_length_exceeded'])||/context.{0,30}(length|limit)|too many (input )?tokens/.test(message))return {message:`This Codex conversation is too long${http}. Continue the task with a shorter conversation.`,terminal:true};
 if(status===404||matches(['model_not_found','model_not_supported'])||/model.{0,40}(not supported|not found|not available)/.test(message))return {message:`The selected Codex model is unavailable${http}. Choose a supported model in AI & model.`,terminal:true};
 if(status===403)return {message:`Codex denied access to this account or model${http}. Check model access and account permissions.`,terminal:true};
 if(matches(['invalid_encrypted_content'])||/encrypted content.{0,50}(invalid|verified|decrypt)|invalid.{0,50}encrypted content/.test(message))return {message:`Codex could not verify the saved reasoning history${http}. Continue from a fresh task context.`,terminal:true};
 if(matches(['unsupported_parameter','unsupported_value'])||/unsupported.{0,60}(parameter|value)|reasoning.{0,60}(unsupported|not supported)/.test(message))return {message:`Codex rejected the selected model settings${http}. Choose a supported reasoning level in AI & model.`,terminal:true};
 if(status===429||matches(['rate_limit_exceeded']))return {message:`Codex is temporarily rate limited${http}. Retry shortly; the saved sign-in was not removed.`,terminal:false};
 if((status!==undefined&&status>=500)||matches(['server_error','internal_error','overloaded','service_unavailable','upstream_error']))return {message:`Codex service is temporarily unavailable${http}. Retry shortly.`,terminal:false};
 if(/invalid.{0,20}image|image.{0,25}(invalid|unsupported|decode)/.test(message))return {message:`Codex rejected a screenshot in this conversation${http}. Retry with a fresh observation.`,terminal:true};
 if(/(tool|function|reasoning).{0,50}(missing|invalid|required|not found)|no tool (call|output)/.test(message))return {message:`Codex rejected the saved tool or reasoning history${http}. Continue from a fresh task context.`,terminal:true};
 return {message:`Codex rejected the inference request${http}. The saved sign-in may still be valid.`,terminal:status!==undefined&&![408,429,500,502,503,504].includes(status)};
}
