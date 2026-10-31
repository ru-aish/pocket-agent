/** Approve only this turn's granted PocketAgent call; never another connector or worker. */
export function pocketApproval(raw:any,sessionId:string,names:readonly string[]) {
 const message=raw?.message??raw;
 const server=message?.metadata?.jit_plugin_data?.from_server??message?.metadata?.elicitation_metadata?.plugin_data?.from_server;
 const body=server?.body,route=typeof body?.params?.path==='string'?body.params.path.split('/'):[];
 const args=body?.params?.args;
 if(message?.author?.role!=='tool'||server?.type!=='confirm_action'||body?.codex_mcp_elicitation?.kind!=='mcpToolCall'||body.codex_mcp_elicitation.status!=='pending'
  ||!['PocketAgent','pocket-agent','pocket_agent'].includes(body?.connector_name)||!body?.connector_id?.startsWith('asdk_app_')||!body?.link_id?.startsWith('link_')
  ||route.length!==4||route[1]!==body.connector_id||route[2]!==body.link_id||!names.includes(route[3])
  ||args?.pocket_session!==sessionId||typeof args?.pocket_request!=='string'||!/^[a-zA-Z0-9._:-]{1,128}$/.test(args.pocket_request)
  ||typeof message.id!=='string'||!body?.actions?.some((action:any)=>action.allow?.target_message_id===message.id))return null;
 return {messageId:message.id,connectorId:body.connector_id,linkId:body.link_id};
}
