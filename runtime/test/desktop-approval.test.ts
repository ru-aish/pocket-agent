import test from 'node:test';
import assert from 'node:assert/strict';
import {pocketApproval} from '../src/desktop-approval.js';
const raw=()=>({id:'approval',author:{role:'tool'},metadata:{jit_plugin_data:{from_server:{type:'confirm_action',body:{connector_name:'PocketAgent',connector_id:'asdk_app_fixture',link_id:'link_fixture',codex_mcp_elicitation:{kind:'mcpToolCall',status:'pending'},params:{path:'/asdk_app_fixture/link_fixture/phone_capabilities',args:{pocket_session:'current-session',pocket_request:'read-1'}},actions:[{allow:{target_message_id:'approval'}}]}}}}});
test('native approval accepts only the exact worker session, granted tool, connector and offered one-use action',()=>{
 assert.deepEqual(pocketApproval(raw(),'current-session',['phone_capabilities']),{messageId:'approval',connectorId:'asdk_app_fixture',linkId:'link_fixture'});
 assert.equal(pocketApproval(raw(),'different-session',['phone_capabilities']),null);assert.equal(pocketApproval(raw(),'current-session',['phone_tap']),null);
 const foreign=raw();foreign.metadata.jit_plugin_data.from_server.body.connector_name='fedora';assert.equal(pocketApproval(foreign,'current-session',['phone_capabilities']),null);
 const changed=raw();changed.metadata.jit_plugin_data.from_server.body.actions[0].allow.target_message_id='different';assert.equal(pocketApproval(changed,'current-session',['phone_capabilities']),null);
});
