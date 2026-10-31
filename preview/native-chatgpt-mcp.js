/** Secret values exist only while this settings pane is mounted. */
export function mountChatGptMcp(parent,{api,el,field,button,heading,note,now=Date.now,setTimer=setTimeout,clearTimer=clearTimeout}){
 let stopped=false,timer,info={},pairing={},refreshing=false;
 heading(parent,'Connect ChatGPT to this phone.','Use PocketAgent’s tools through the ChatGPT MCP connector.');
 const status=el('p','settings-description','Reading connection status…');status.setAttribute('role','status');parent.append(status);
 const endpoint=field(parent,'MCP URL','',{placeholder:'Start the connection to get its HTTPS URL'});endpoint.readOnly=true;endpoint.autocomplete='off';
 const copyUrl=button('Copy MCP URL',()=>api('/native/chatgpt-mcp/copy',{what:'url'}));parent.append(copyUrl);
 const code=el('output','mcp-pairing-code','—'),expires=el('p','settings-description','');code.setAttribute('aria-label','One-time pairing code');
 parent.append(el('h3','connection-title','Pairing code'),code,expires);
 const copyCode=button('Copy pairing code',()=>api('/native/chatgpt-mcp/copy',{what:'code'}));
 const rotate=button('Get a new code',async()=>{const next=await api('/native/chatgpt-mcp/rotate');if(!stopped){pairing=next;paint();}});parent.append(copyCode,rotate);
 parent.append(el('p','settings-native-note','Add the MCP URL as a custom app in ChatGPT and choose OAuth. In the authorization page, enter this code. It changes every five minutes and works once. Existing connections renew their tokens automatically.'));
 const start=button('Start MCP connection',async()=>{await api('/native/chatgpt-mcp/start');await refresh();},'settings-primary');
 const stop=button('Stop MCP connection',async()=>{await api('/native/chatgpt-mcp/stop');pairing={};await refresh();});
 const revoke=button('Revoke all connections',async()=>{await api('/native/chatgpt-mcp/revoke');pairing={};await refresh();note('ChatGPT access revoked. Pair again to reconnect.');});parent.append(start,stop,revoke);
 const setup=el('details','connection-card');setup.append(el('summary','','Connection address'));parent.append(setup);
 setup.append(el('p','settings-description','Use an ngrok token to create a reachable HTTPS URL, or forward a public HTTPS gateway to this phone’s local MCP server at 127.0.0.1:8768.'));
 const token=field(setup,'ngrok token','',{password:true,placeholder:'Leave blank to keep the saved token'});token.autocomplete='off';
 const origin=field(setup,'Public HTTPS gateway origin','',{placeholder:'https://phone.example.com'});
 const externalWrap=el('label','settings-switch-row'),external=el('input');external.type='checkbox';externalWrap.append(el('span','','Use the public gateway instead of ngrok'),external);setup.append(externalWrap);
 const save=button('Save connection address',async()=>{await api('/native/chatgpt-mcp/save',{ngrokToken:token.value,publicOrigin:origin.value,externalGateway:external.checked});token.value='';await refresh();note('Connection address saved.');});setup.append(save);
 let initialized=false;
 function paint(){
  if(stopped)return;
  const online=info.stage==='online',running=!['stopped','failed',undefined].includes(info.stage);
  endpoint.value=online?info.publicUrl||'':'';copyUrl.disabled=!online;
  status.textContent=info.diagnostic||({online:`MCP online · ${info.connections||0} authorized connection${info.connections===1?'':'s'}`,stopped:'MCP connection stopped',failed:'Connection failed. Check the saved address and phone network.',starting:'Starting MCP connection…',local:'Preparing the public address…',connecting:'Connecting to the public address…'})[info.stage]||'MCP connection stopped';
  const seconds=Math.max(0,Math.ceil(((pairing.expiresAt||0)-now())/1000));
  code.textContent=online&&seconds?pairing.code:'—';expires.textContent=online&&seconds?`Expires in ${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')} · One use`:'Start the connection to get a pairing code.';
  copyCode.disabled=!online||!seconds;rotate.disabled=!online;start.disabled=running;stop.disabled=!running;
  token.disabled=running;origin.disabled=running;external.disabled=running;save.disabled=running;
 }
 async function refresh(){
  if(stopped||refreshing)return;refreshing=true;
  try{
   const next=await api('/native/chatgpt-mcp/status');if(stopped)return;info=next;
   if(!initialized){origin.value=info.publicOrigin||'';external.checked=!info.tunnelConfigured;initialized=true;}
   if(info.stage==='online'){const nextPair=await api('/native/chatgpt-mcp/pairing');if(stopped)return;pairing=nextPair;}else pairing={};
   paint();
  }catch(error){if(!stopped)status.textContent=error.message;}finally{refreshing=false;}
 }
 let ticks=0;
 async function tick(){if(stopped)return;if(!parent.ownerDocument.hidden){paint();if(++ticks%5===0||((pairing.expiresAt||0)<=now()&&info.stage==='online'))await refresh();}if(!stopped)timer=setTimer(tick,1000);}
 void refresh();timer=setTimer(tick,1000);
 return ()=>{stopped=true;clearTimer(timer);pairing={};token.value='';code.textContent='—';endpoint.value='';};
}
