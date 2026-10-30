export function reconcileMessages(parent,items,label='Assistant') {
  let changed=false;
  for(const child of [...parent.children])if(!child.matches('article.message')||!child.querySelector('.message-body')){child.remove();changed=true;}
  items.forEach((item,index)=>{
    let article=parent.children[index];
    if(!article){article=document.createElement('article');article.className='message';const role=document.createElement('h2');role.className='message-label sr-only';const body=document.createElement('p');body.className='message-body';article.append(role,body);parent.append(article);changed=true;}
    const role=item.role==='user'?'You':label,text=item.text||item.content||'';
    article.dataset.role=item.role;
    if(article.firstElementChild.textContent!==role)article.firstElementChild.textContent=role;
    if(article.querySelector('.message-body').textContent!==text){article.querySelector('.message-body').textContent=text;changed=true;}
  });
  while(parent.children.length>items.length){parent.lastElementChild.remove();changed=true;}
  return changed;
}
export function renderChatHistory(parent,chats,{currentId,onOpen,onRename,onPin,onDelete,onError=console.warn}) {
  parent.replaceChildren();
  const sorted=[...chats].sort((a,b)=>Number(!!b.pinned)-Number(!!a.pinned));
  const pinned=sorted.filter(chat=>chat.pinned);
  let pinnedList=null;
  if(pinned.length){
    const section=document.createElement('section');section.className='pinned-chats';let expanded=true;
    try{expanded=localStorage.getItem('pocket-pinned-expanded')!=='false';}catch{}
    const summary=document.createElement('button');summary.type='button';summary.className='pinned-toggle';summary.setAttribute('aria-label','Pinned chats');summary.setAttribute('aria-expanded',String(expanded));
    summary.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg><span>Pinned</span>';
    pinnedList=document.createElement('div');pinnedList.className='pinned-chat-list';pinnedList.hidden=!expanded;section.append(summary,pinnedList);parent.append(section);
    summary.onclick=()=>{expanded=!expanded;pinnedList.hidden=!expanded;summary.setAttribute('aria-expanded',String(expanded));try{localStorage.setItem('pocket-pinned-expanded',String(expanded));}catch{}};
    if(sorted.some(chat=>!chat.pinned)){const label=document.createElement('p');label.className='history-section-label';label.textContent='Recents';parent.append(label);}
  }
  if(parent.previousElementSibling?.classList.contains('drawer-label'))parent.previousElementSibling.hidden=pinned.length>0;
  for(const chat of sorted){
    const row=document.createElement('div');row.className='history-row';row.dataset.chat=chat.id;
    const title=document.createElement('button');title.type='button';title.className='history-item';title.textContent=chat.title;title.title=chat.title;
    if(chat.pinned){title.textContent='';const glyph=document.createElementNS('http://www.w3.org/2000/svg','svg');glyph.setAttribute('viewBox','0 0 24 24');glyph.setAttribute('aria-hidden','true');glyph.innerHTML='<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5 9 9 0 0 1-4-.9L3 21l1.9-5.5a9 9 0 0 1-.9-4A8.5 8.5 0 0 1 12.5 3H13a8.5 8.5 0 0 1 8 8v.5Z"/>';const label=document.createElement('span');label.className='history-title';label.textContent=chat.title;title.append(glyph,label);}
    if(chat.id===currentId)title.setAttribute('aria-current','page');
    let timer=0,start=null,suppressUntil=0;
    title.setAttribute('aria-haspopup','menu');title.setAttribute('aria-label',`${chat.title}. Hold for chat options.`);
    const cancelHold=()=>{clearTimeout(timer);timer=0;start=null;};
    const openMenu=()=>{cancelHold();suppressUntil=performance.now()+800;const workspace=parent.closest('.workspace'),layer=document.createElement('div');layer.className='chat-context-layer';const menu=document.createElement('section');menu.className='chat-context-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label','Chat options');const caption=document.createElement('p');caption.textContent=chat.title;menu.append(caption);
      const siblings=Array.from(workspace.children).map(n=>[n,n.inert]),previousBack=window.pocketBack;for(const[n]of siblings)n.inert=true;
      const close=()=>{layer.remove();for(const[n,inert]of siblings)n.inert=inert;if(window.pocketBack===close)window.pocketBack=previousBack;title.focus({preventScroll:true});};window.pocketBack=close;
      for(const[label,action,kind]of [['Rename',onRename,'rename'],[chat.pinned?'Unpin':'Pin',onPin,'pin'],['Delete',onDelete,'delete']]){const b=document.createElement('button');b.type='button';b.textContent=label;b.dataset.chatAction=kind;b.setAttribute('role','menuitem');b.onclick=()=>{close();void Promise.resolve().then(()=>action(chat)).catch(onError);};menu.append(b);}
      layer.onclick=event=>{if(event.target===layer)close();};layer.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();}if(['Tab','ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();const buttons=Array.from(menu.querySelectorAll('button')),index=buttons.indexOf(document.activeElement),step=event.key==='ArrowUp'||event.shiftKey?-1:1;buttons[(index+step+buttons.length)%buttons.length].focus();}};layer.append(menu);workspace.append(layer);menu.querySelector('button').focus({preventScroll:true});
    };
    title.onclick=event=>{if(performance.now()<suppressUntil){event.preventDefault();return;}void Promise.resolve().then(()=>onOpen(chat)).catch(onError);};
    title.onpointerdown=event=>{if(!event.isPrimary)return;cancelHold();start={x:event.clientX,y:event.clientY};timer=setTimeout(openMenu,450);};
    title.onpointermove=event=>{if(start&&Math.hypot(event.clientX-start.x,event.clientY-start.y)>8)cancelHold();};
    title.onpointerup=title.onpointercancel=cancelHold;
    title.oncontextmenu=event=>{event.preventDefault();if(!parent.closest('.workspace').querySelector('.chat-context-layer'))openMenu();};
    row.append(title);(chat.pinned?pinnedList:parent).append(row);
  }
}
export function renameChat(workspace,currentTitle){
  return new Promise(resolve=>{
    const layer=document.createElement('div');layer.className='chat-confirm-layer';const panel=document.createElement('section');panel.className='chat-confirm';panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-label','Rename chat');
    const label=document.createElement('label');label.textContent='Rename chat';label.htmlFor='chat-rename-input';const input=document.createElement('input');input.id='chat-rename-input';input.value=currentTitle;input.maxLength=80;input.autocomplete='off';const actions=document.createElement('div');actions.className='chat-confirm-actions';const cancel=document.createElement('button'),save=document.createElement('button');cancel.type=save.type='button';cancel.textContent='Cancel';save.textContent='Save';save.dataset.chatRename='save';
    const previous=document.activeElement,previousBack=window.pocketBack,siblings=Array.from(workspace.children).map(n=>[n,n.inert]);for(const[n]of siblings)n.inert=true;
    const finish=value=>{input.blur();layer.remove();for(const[n,inert]of siblings)n.inert=inert;if(window.pocketBack===back)window.pocketBack=previousBack;previous?.focus({preventScroll:true});if(window.pocketRequest)void window.pocketRequest('/native/keyboard-hide').catch(console.warn);resolve(value);};const back=()=>finish(null);window.pocketBack=back;
    cancel.onclick=back;save.onclick=()=>{const value=input.value.trim().replace(/\s+/g,' ');if(value)finish(value);else input.focus();};input.oninput=()=>save.disabled=!input.value.trim();layer.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();back();}else if(event.key==='Enter'){event.preventDefault();save.click();}else if(event.key==='Tab'){event.preventDefault();const controls=[input,cancel,save].filter(n=>!n.disabled),index=controls.indexOf(document.activeElement);controls[(index+(event.shiftKey?-1:1)+controls.length)%controls.length].focus();}};layer.onclick=event=>{if(event.target===layer)back();};actions.append(cancel,save);panel.append(label,input,actions);layer.append(panel);workspace.append(layer);input.focus({preventScroll:true});input.select();
  });
}
export function createChatSearch({screen,search,onSelect,onClose}){
  screen.innerHTML='<header class="chat-search-header"><h1>Search chats</h1></header><div class="chat-search-results" aria-label="Chat results"></div><p class="chat-search-status" role="status"></p><div class="chat-search-dock"><label class="chat-search-field"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16 16 5 5"/></svg><span class="sr-only">Search chats</span><input id="chat-search-query" type="search" placeholder="Search chats" autocomplete="off" enterkeyhint="search" maxlength="300"><button type="button" class="search-clear" aria-label="Clear search" hidden>×</button></label><button type="button" class="search-cancel" aria-label="Close search"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6"/></svg></button></div>';
  const input=screen.querySelector('input'),list=screen.querySelector('.chat-search-results'),status=screen.querySelector('[role=status]'),clear=screen.querySelector('.search-clear');let generation=0,timer=0,previousBack;
  const close=(selected=false)=>{clearTimeout(timer);generation++;screen.hidden=true;input.blur();if(window.pocketBack===back)window.pocketBack=previousBack;if(window.pocketRequest)void window.pocketRequest('/native/keyboard-hide').catch(console.warn);onClose(selected);};
  const back=()=>close(false);
  const run=async()=>{const request=++generation,query=input.value.trim();clear.hidden=!input.value;status.textContent=query?'Searching…':'Recent chats';try{const chats=await search(query);if(request!==generation||screen.hidden)return;list.replaceChildren();for(const chat of chats){const button=document.createElement('button');button.type='button';button.className='chat-search-result';const title=document.createElement('strong');title.textContent=chat.title;const snippet=document.createElement('small');snippet.textContent=chat.snippet||`${chat.count||0} messages`;button.append(title,snippet);button.onclick=()=>{close(true);void Promise.resolve().then(()=>onSelect(chat)).catch(error=>status.textContent=error.message);};list.append(button);}status.textContent=chats.length?'':query?'No chats found. Try another word.':'No saved chats yet.';}catch(error){if(request===generation&&!screen.hidden)status.textContent=error.message;}};
  input.oninput=()=>{clearTimeout(timer);generation++;clear.hidden=!input.value;timer=setTimeout(run,100);};input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();clearTimeout(timer);void run();input.blur();if(window.pocketRequest)void window.pocketRequest('/native/keyboard-hide').catch(console.warn);}};
  clear.onclick=()=>{input.value='';input.focus();void run();};screen.querySelector('.search-cancel').onclick=back;
  return{open(){previousBack=window.pocketBack;window.pocketBack=back;screen.hidden=false;input.value='';clear.hidden=true;input.focus({preventScroll:true});void run();},close:back};
}
export function confirmChatDelete(workspace,title){
  return new Promise(resolve=>{
    const previous=document.activeElement,previousBack=window.pocketBack,overlay=document.createElement('div');overlay.className='chat-confirm-layer';
    const panel=document.createElement('section');panel.className='chat-confirm';panel.setAttribute('role','alertdialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-label','Delete chat');
    const heading=document.createElement('h2');heading.textContent='Delete chat?';const copy=document.createElement('p');copy.textContent=`“${title}” will be removed from your chats. You can undo this.`;
    const actions=document.createElement('div');actions.className='chat-confirm-actions';
    const cancel=document.createElement('button'),remove=document.createElement('button');cancel.type=remove.type='button';cancel.textContent='Cancel';remove.textContent='Delete';remove.className='delete-chat';
    const siblings=Array.from(workspace.children).map(node=>[node,node.inert]);for(const[node]of siblings)node.inert=true;
    const finish=value=>{overlay.remove();for(const[node,inert]of siblings)node.inert=inert;if(window.pocketBack===cancelBack)window.pocketBack=previousBack;previous?.focus({preventScroll:true});resolve(value);};
    const cancelBack=()=>finish(false);window.pocketBack=cancelBack;
    cancel.onclick=()=>finish(false);remove.onclick=()=>finish(true);
    overlay.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();finish(false);}if(event.key==='Tab'){event.preventDefault();(document.activeElement===cancel?remove:cancel).focus();}};
    overlay.onclick=event=>{if(event.target===overlay)finish(false);};
    actions.append(cancel,remove);panel.append(heading,copy,actions);overlay.append(panel);workspace.append(overlay);cancel.focus({preventScroll:true});
  });
}
export function showChatUndo(workspace,onUndo,onError=console.warn){
  workspace.querySelector('.history-undo')?.remove();
  const bar=document.createElement('div');bar.className='history-undo';bar.setAttribute('role','status');
  const text=document.createElement('span');text.textContent='Chat deleted';const undo=document.createElement('button');undo.type='button';undo.textContent='Undo';
  undo.onclick=async()=>{undo.disabled=true;try{await onUndo();bar.remove();}catch(error){undo.disabled=false;onError(error);}};
  const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','Dismiss');close.onclick=()=>bar.remove();bar.append(text,undo,close);workspace.append(bar);
}
export function mountComposer(input) {
  document.addEventListener('keydown',event=>{if(event.key==='Tab')document.documentElement.dataset.keyboardNavigation='true';});
  document.addEventListener('pointerdown',()=>{delete document.documentElement.dataset.keyboardNavigation;},{passive:true});
  const form=input.closest('form');
  let keyboard=null,nativeKeyboard=false,nativeVisible=false,frame=0,measuredValue=null,measuredWidth=0,viewportHeight=window.visualViewport?.height||innerHeight,viewportWidth=innerWidth;
  input.rows=1;
  const measure=()=>{frame=0;const expanded=keyboard??document.activeElement===input;form.classList.toggle('is-expanded',expanded);form.classList.toggle('has-draft',!!input.value);const width=input.clientWidth;if(input.value===measuredValue&&width===measuredWidth)return;measuredValue=input.value;measuredWidth=width;input.style.setProperty('--composer-input-height','0px');input.style.setProperty('--composer-input-height',input.value?Math.min(160,Math.max(40,input.scrollHeight))+'px':'40px');};
  const resize=()=>{if(!frame)frame=requestAnimationFrame(measure);};
  const dismiss=()=>{keyboard=false;input.blur();resize();if(window.pocketRequest)void window.pocketRequest('/native/keyboard-hide').catch(error=>console.warn('Keyboard dismissal failed',error));};
  input.addEventListener('focus',()=>{keyboard=null;resize();});input.addEventListener('blur',()=>{keyboard=false;resize();});input.addEventListener('input',resize);
  window.addEventListener('pocket-keyboard',event=>{nativeKeyboard=true;nativeVisible=event.detail.visible;keyboard=nativeVisible;if(!keyboard&&document.activeElement===input)input.blur();resize();});
  window.visualViewport?.addEventListener('resize',()=>{if(nativeKeyboard||window.visualViewport.scale!==1)return;if(Math.abs(innerWidth-viewportWidth)>50){viewportWidth=innerWidth;viewportHeight=window.visualViewport.height;keyboard=null;}else{const height=window.visualViewport.height;if(height>viewportHeight-60&&keyboard===true){keyboard=false;input.blur();}else if(viewportHeight-height>120&&document.activeElement===input)keyboard=true;viewportHeight=Math.max(viewportHeight,height);}resize();});
  let gesture;
  const conversation=document.querySelector('#conversation');
  conversation?.addEventListener('touchstart',event=>{gesture=null;const visible=nativeKeyboard?nativeVisible:keyboard===true||document.activeElement===input;if(event.touches.length!==1||!visible||event.target.closest('button,a,input,textarea,select,[contenteditable=true]'))return;gesture={x:event.touches[0].clientX,y:event.touches[0].clientY};},{passive:true});
  conversation?.addEventListener('touchmove',event=>{if(!gesture||event.touches.length!==1)return;const dx=event.touches[0].clientX-gesture.x,dy=event.touches[0].clientY-gesture.y;if(dy>40&&dy>Math.abs(dx)*1.3){gesture=null;dismiss();}},{passive:true});
  conversation?.addEventListener('touchend',()=>{gesture=null;},{passive:true});conversation?.addEventListener('touchcancel',()=>{gesture=null;},{passive:true});
  window.addEventListener('resize',resize);
  window.addEventListener('pocket-composer-reset',dismiss);measure();return resize;
}

export function mountThinkingIndicator(conversation) {
  const indicator=document.createElement('div');indicator.className='thinking-indicator';indicator.hidden=true;
  indicator.setAttribute('role','status');indicator.setAttribute('aria-label','Pocket is thinking or responding');
  for(let i=0;i<3;i++){const dot=document.createElement('span');dot.className='thinking-dot';dot.setAttribute('aria-hidden','true');indicator.append(dot);}
  conversation.append(indicator);
  return active=>{const visible=!!active;if(indicator.hidden===!visible)return;const follow=conversation.scrollHeight-conversation.scrollTop-conversation.clientHeight<100;indicator.hidden=!visible;if(visible&&follow)conversation.scrollTop=conversation.scrollHeight;};
}
