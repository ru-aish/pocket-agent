export function reconcileMessages(parent,items,label='Assistant') {
  let changed=false;
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
export function mountComposer(input) {
  const form=input.closest('form');
  let keyboard=null,nativeKeyboard=false,nativeVisible=false,frame=0,viewportHeight=window.visualViewport?.height||innerHeight,viewportWidth=innerWidth;
  input.rows=1;
  const measure=()=>{frame=0;const expanded=keyboard??document.activeElement===input;form.classList.toggle('is-expanded',expanded);input.style.setProperty('--composer-input-height','0px');input.style.setProperty('--composer-input-height',Math.min(160,Math.max(expanded?64:40,input.scrollHeight))+'px');};
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
  window.addEventListener('pocket-composer-reset',dismiss);measure();return resize;
}

export function mountThinkingIndicator(conversation) {
  const indicator=document.createElement('div');indicator.className='thinking-indicator';indicator.hidden=true;
  indicator.setAttribute('role','status');indicator.setAttribute('aria-label','Pocket is thinking or responding');
  for(let i=0;i<3;i++){const dot=document.createElement('span');dot.className='thinking-dot';dot.setAttribute('aria-hidden','true');indicator.append(dot);}
  conversation.append(indicator);
  return active=>{const visible=!!active;if(indicator.hidden===!visible)return;const follow=conversation.scrollHeight-conversation.scrollTop-conversation.clientHeight<100;indicator.hidden=!visible;if(visible&&follow)conversation.scrollTop=conversation.scrollHeight;};
}
